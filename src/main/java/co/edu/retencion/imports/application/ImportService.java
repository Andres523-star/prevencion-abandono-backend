package co.edu.retencion.imports.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.imports.domain.FieldSpec;
import co.edu.retencion.imports.domain.ImportJob;
import co.edu.retencion.imports.domain.ImportJobRepository;
import co.edu.retencion.imports.infrastructure.ImportJdbc;

/**
 * Recepción de archivos, estado de los trabajos, reportes y plantillas.
 */
@Service
public class ImportService {

    private static final TypeReference<Map<String, String>> MAPPING = new TypeReference<>() {
    };

    private final ImportJobRepository jobs;
    private final ImportJdbc jdbc;
    private final ImportProcessor processor;
    private final ImportProperties properties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ImportService(ImportJobRepository jobs, ImportJdbc jdbc, ImportProcessor processor,
                         ImportProperties properties, ObjectMapper objectMapper, PlatformTransactionManager txManager,
                         Clock clock) {
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.processor = processor;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /**
     * Estado y contadores de un trabajo.
     *
     * @param id             id del trabajo (para consultar el estado y el reporte)
     * @param type           tipo de importación
     * @param dryRun         solo validación
     * @param status         PENDING, PROCESSING, COMPLETED o FAILED
     * @param totalRows      filas de datos
     * @param createdRows    creadas
     * @param updatedRows    actualizadas
     * @param unchangedRows  sin cambios (reimportación)
     * @param validRows      válidas (dry-run)
     * @param errorRows      con error (ver reporte)
     * @param failureReason  motivo si el trabajo completo falló
     */
    public record ImportJobView(UUID id, ImportJob.Type type, ImportJob.Format format, String fileName, boolean dryRun,
                                ImportJob.Status status, int totalRows, int createdRows, int updatedRows,
                                int unchangedRows, int validRows, int errorRows, String failureReason,
                                Instant createdAt, Instant startedAt, Instant finishedAt) {
        static ImportJobView of(ImportJob j) {
            return new ImportJobView(j.getId(), j.getType(), j.getFormat(), j.getFileName(), j.isDryRun(), j.getStatus(),
                    j.getTotalRows(), j.getCreatedRows(), j.getUpdatedRows(), j.getUnchangedRows(), j.getValidRows(),
                    j.getErrorRows(), j.getFailureReason(), j.getCreatedAt(), j.getStartedAt(), j.getFinishedAt());
        }
    }

    public ImportJobView submit(ImportJob.Type type, String fileName, byte[] content, boolean dryRun,
                                String columnMapping) {
        if (content.length == 0) {
            throw DomainException.badRequest("El archivo está vacío");
        }
        if (content.length > properties.maxFileSize().toBytes()) {
            throw DomainException.badRequest("El archivo supera el tamaño máximo de " + properties.maxFileSize());
        }
        ImportJob.Format format = detectFormat(fileName, content);
        validateMapping(columnMapping);
        boolean inline = content.length <= properties.syncMaxSize().toBytes();
        UUID actor = CurrentUser.require().userId();
        String safeName = fileName == null ? null : fileName.replaceAll("[^A-Za-z0-9._ -]", "_")
                .substring(0, Math.min(fileName.length(), 200));

        ImportJob job = ImportJob.submit(type, format, safeName, sha256(content), dryRun,
                columnMapping == null || columnMapping.isBlank() ? null : columnMapping, actor, clock.instant());
        if (inline) {
            // Se marca en proceso desde el inicio para que el worker no lo tome.
            job.start(clock.instant());
        }
        tx.executeWithoutResult(s -> {
            jobs.save(job);
            jobs.flush();
            jdbc.saveFile(job.getId(), content);
        });
        if (inline) {
            processor.process(job.getId());
        }
        return get(job.getId());
    }

    public ImportJobView get(UUID id) {
        return jobs.findById(id).map(ImportJobView::of)
                .orElseThrow(() -> DomainException.notFound("Importación no encontrada"));
    }

    /** Reporte por fila en CSV: fila, resultado, mensaje (sin valores de la fila). */
    public String reportCsv(UUID id) {
        get(id);
        StringBuilder csv = new StringBuilder("fila,resultado,mensaje\n");
        for (ImportJdbc.RowResult r : jdbc.results(id)) {
            csv.append(r.rowNumber()).append(',').append(r.outcome().result()).append(',')
                    .append(quote(r.outcome().message())).append('\n');
        }
        return csv.toString();
    }

    /** Plantilla oficial: encabezados canónicos y una fila de ejemplo. */
    public String templateCsv(ImportJob.Type type) {
        var fields = processor.handler(type).fields();
        String headers = fields.stream().map(FieldSpec::name).collect(Collectors.joining(","));
        String example = fields.stream().map(f -> quote(f.example())).collect(Collectors.joining(","));
        return headers + "\n" + example + "\n";
    }

    /** Por "magic bytes", no por la extensión: un .xlsx es un ZIP (PK\x03\x04). */
    static ImportJob.Format detectFormat(String fileName, byte[] content) {
        boolean zip = content.length > 4 && content[0] == 'P' && content[1] == 'K' && content[2] == 3 && content[3] == 4;
        boolean claimsExcel = fileName != null && fileName.toLowerCase().endsWith(".xlsx");
        if (zip) {
            return ImportJob.Format.XLSX;
        }
        if (claimsExcel) {
            throw DomainException.badRequest("El archivo dice ser Excel pero su contenido no lo es");
        }
        for (int i = 0; i < Math.min(content.length, 8192); i++) {
            if (content[i] == 0) {
                throw DomainException.badRequest("Formato no soportado: use CSV (UTF-8) o Excel (.xlsx)");
            }
        }
        return ImportJob.Format.CSV;
    }

    private void validateMapping(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            Map<String, String> mapping = objectMapper.readValue(json, MAPPING);
            if (mapping.size() > 50) {
                throw DomainException.badRequest("Demasiadas columnas en el mapeo");
            }
        } catch (DomainException ex) {
            throw ex;
        } catch (Exception ex) {
            throw DomainException.badRequest("El mapeo debe ser un objeto JSON {\"campo\": \"encabezado\"}");
        }
    }

    private static String quote(String value) {
        if (value == null) {
            return "";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
