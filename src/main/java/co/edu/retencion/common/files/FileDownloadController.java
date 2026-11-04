package co.edu.retencion.common.files;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import co.edu.retencion.common.error.DomainException;

/**
 * Descarga con URL firmada del almacenamiento local (equivalente a la URL prefirmada de S3).
 * Solo existe con {@code app.storage.type=local}.
 */
@RestController
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
@Tag(name = "Archivos")
public class FileDownloadController {

    private final LocalObjectStorage storage;

    public FileDownloadController(LocalObjectStorage storage) {
        this.storage = storage;
    }

    @GetMapping("/api/v1/files/{token}")
    @SecurityRequirements
    @Operation(summary = "Descarga un archivo con un enlace firmado de corta duración")
    public ResponseEntity<byte[]> download(@PathVariable String token) {
        LocalObjectStorage.SignedDownload download = storage.verify(token)
                .orElseThrow(() -> DomainException.notFound("El enlace no es válido o ya venció"));
        byte[] content = storage.get(download.key());
        MediaType type = FileTypes.detect(content).map(k -> MediaType.parseMediaType(k.mediaType()))
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(download.fileName()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(content);
    }
}
