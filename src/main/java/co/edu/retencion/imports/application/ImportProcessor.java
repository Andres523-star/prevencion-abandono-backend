package co.edu.retencion.imports.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import co.edu.retencion.audit.AuditOutcome;
import co.edu.retencion.audit.AuditTrail;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.imports.domain.ColumnMapping;
import co.edu.retencion.imports.domain.ImportJob;
import co.edu.retencion.imports.domain.ImportJobRepository;
import co.edu.retencion.imports.domain.RowOutcome;
import co.edu.retencion.imports.domain.RowValues;
import co.edu.retencion.imports.infrastructure.ImportJdbc;
import co.edu.retencion.imports.infrastructure.ImportJdbc.RowResult;
import co.edu.retencion.imports.infrastructure.TableReader;

/**
 * Procesa un trabajo de importación: lee el archivo, mapea columnas, procesa cada fila en su
 * propia transacción (un error no revierte las demás; la idempotencia permite reintentar) y deja
 * el reporte por fila. Al final borra el archivo, que contiene datos personales.
 */
@Component
class ImportProcessor {

    private static final Logger log = LoggerFactory.getLogger(ImportProcessor.class);
    private static final int FLUSH_EVERY = 1_000;
    private static final TypeReference<Map<String, String>> MAPPING = new TypeReference<>() {
    };

    private final ImportJobRepository jobs;
    private final ImportJdbc jdbc;
    private final Map<ImportJob.Type, RowHandler> handlers;
    private final ImportProperties properties;
    private final AuditTrail audit;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final TransactionTemplate rowTx;
    private final Clock clock;

    ImportProcessor(ImportJobRepository jobs, ImportJdbc jdbc, List<RowHandler> handlers, ImportProperties properties,
                    AuditTrail audit, ObjectMapper objectMapper, PlatformTransactionManager txManager, Clock clock) {
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.handlers = handlers.stream().collect(Collectors.toMap(RowHandler::type, Function.identity()));
        this.properties = properties;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
        this.rowTx = new TransactionTemplate(txManager);
        this.rowTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    RowHandler handler(ImportJob.Type type) {
        return handlers.get(type);
    }

    void process(UUID jobId) {
        ImportJob job = jobs.findById(jobId).orElseThrow();
        try {
            byte[] content = jdbc.loadFile(jobId)
                    .orElseThrow(() -> DomainException.badRequest("El archivo ya no está disponible"));
            TableReader.Table table = TableReader.forFormat(job.getFormat()).read(content);
            RowHandler handler = handlers.get(job.getType());
            ColumnMapping.Resolution mapping = ColumnMapping.resolve(table.headers(), handler.fields(),
                    properties.aliases(), parseMapping(job.getColumnMapping()));
            if (!mapping.isComplete()) {
                finishFailed(jobId, "Faltan columnas obligatorias: " + String.join(", ", mapping.missingRequired()));
                return;
            }
            int[] counts = processRows(job, handler, table, mapping);
            tx.executeWithoutResult(s -> jobs.findById(jobId).orElseThrow()
                    .complete(table.rows().size(), counts, clock.instant()));
            audit.recordAs(job.getRequestedBy(), "IMPORT_PROCESSED", "ImportJob", jobId.toString(),
                    counts[4] == 0 ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE,
                    Map.of("tipo", job.getType().name(), "filas", Integer.toString(table.rows().size()),
                            "errores", Integer.toString(counts[4]), "dryRun", Boolean.toString(job.isDryRun())));
        } catch (DomainException ex) {
            finishFailed(jobId, ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Fallo inesperado procesando la importación {}", jobId, ex);
            finishFailed(jobId, "Error inesperado al procesar el archivo");
        } finally {
            jdbc.deleteFile(jobId);
        }
    }

    private int[] processRows(ImportJob job, RowHandler handler, TableReader.Table table,
                              ColumnMapping.Resolution mapping) {
        RowHandler.Context context = RowHandler.Context.of(job.isDryRun(), job.getRequestedBy());
        int[] counts = new int[5];
        List<RowResult> pending = new ArrayList<>();
        for (TableReader.Row row : table.rows()) {
            RowValues values = new RowValues(row.number(), extract(row, mapping.columnIndex()));
            RowOutcome outcome = processRow(handler, values, context);
            counts[outcome.result().ordinal()]++;
            pending.add(new RowResult(row.number(), outcome));
            if (pending.size() >= FLUSH_EVERY) {
                jdbc.writeResults(job.getId(), pending);
                pending.clear();
            }
        }
        jdbc.writeResults(job.getId(), pending);
        return counts;
    }

    private RowOutcome processRow(RowHandler handler, RowValues values, RowHandler.Context context) {
        try {
            return rowTx.execute(status -> handler.process(values, context));
        } catch (DomainException ex) {
            return RowOutcome.error(ex.getMessage());
        } catch (DataIntegrityViolationException ex) {
            return RowOutcome.error("conflicto con datos existentes");
        } catch (RuntimeException ex) {
            log.warn("Error inesperado en la fila {}: {}", values.rowNumber(), ex.getClass().getSimpleName());
            return RowOutcome.error("error inesperado al procesar la fila");
        }
    }

    private static Map<String, String> extract(TableReader.Row row, Map<String, Integer> columns) {
        Map<String, String> values = new HashMap<>();
        columns.forEach((field, index) -> {
            if (index < row.cells().size()) {
                values.put(field, row.cells().get(index));
            }
        });
        return values;
    }

    private Map<String, String> parseMapping(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAPPING);
        } catch (Exception ex) {
            throw DomainException.badRequest("El mapeo de columnas no es válido");
        }
    }

    private void finishFailed(UUID jobId, String reason) {
        tx.executeWithoutResult(s -> jobs.findById(jobId).orElseThrow().fail(reason, clock.instant()));
    }
}
