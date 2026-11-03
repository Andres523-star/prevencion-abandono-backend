package co.edu.retencion.imports.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import co.edu.retencion.audit.Audited;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.imports.application.ImportService;
import co.edu.retencion.imports.application.ImportService.ImportJobView;
import co.edu.retencion.imports.domain.ImportJob;

/**
 * Importación masiva (solo ADMIN). Respuesta 200 si el archivo se procesó en la petición o 202
 * si quedó en cola (consultar {@code GET /imports/{id}} hasta COMPLETED o FAILED).
 */
@RestController
@RequestMapping("/api/v1/imports")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Importación masiva")
public class ImportController {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final ImportService imports;

    public ImportController(ImportService imports) {
        this.imports = imports;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Audited(action = "IMPORT_SUBMIT", entity = "ImportJob", entityId = "#result.body.id")
    @Operation(summary = "Sube un CSV o Excel para importar (dryRun=true solo valida)")
    public ResponseEntity<ImportJobView> submit(@RequestParam ImportJob.Type type,
                                                @RequestParam(defaultValue = "false") boolean dryRun,
                                                @RequestParam(required = false) String mapping,
                                                @RequestPart("file") MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            throw DomainException.badRequest("No se pudo leer el archivo");
        }
        ImportJobView job = imports.submit(type, file.getOriginalFilename(), content, dryRun, mapping);
        HttpStatus status = job.status() == ImportJob.Status.PENDING || job.status() == ImportJob.Status.PROCESSING
                ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(job);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Estado y contadores de una importación")
    public ImportJobView get(@PathVariable UUID id) {
        return imports.get(id);
    }

    @GetMapping("/{id}/report")
    @Operation(summary = "Reporte por fila en CSV (sin datos personales)")
    public ResponseEntity<String> report(@PathVariable UUID id) {
        return ResponseEntity.ok().contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"importacion-" + id + ".csv\"")
                .body(imports.reportCsv(id));
    }

    @GetMapping("/templates/{type}")
    @Operation(summary = "Plantilla CSV con los encabezados oficiales y una fila de ejemplo")
    public ResponseEntity<String> template(@PathVariable ImportJob.Type type) {
        return ResponseEntity.ok().contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"plantilla-"
                        + type.name().toLowerCase() + ".csv\"")
                .body(imports.templateCsv(type));
    }
}
