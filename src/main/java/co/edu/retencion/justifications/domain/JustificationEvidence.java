package co.edu.retencion.justifications.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import co.edu.retencion.common.id.UuidV7;

/**
 * Evidencia de una justificación. El archivo vive en el almacenamiento de objetos con nombre
 * aleatorio; aquí solo hay metadatos (tipo real, tamaño, hash y estado del antivirus). Solo se
 * puede descargar si el antivirus la marcó como LIMPIO.
 */
@Entity
@Table(name = "justification_evidences")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JustificationEvidence {

    /** Estado del escaneo antivirus. */
    public enum ScanStatus {
        PENDIENTE,
        LIMPIO,
        INFECTADO,
        /** El antivirus no respondió: se reintenta en segundo plano. */
        ERROR
    }

    @Id
    private UUID id;

    @Column(name = "justification_id", nullable = false)
    private UUID justificationId;

    @Column(name = "object_key", nullable = false, unique = true)
    private String objectKey;

    @Column(name = "media_type", nullable = false)
    private String mediaType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "scan_status", nullable = false)
    private ScanStatus scanStatus;

    @Column(name = "uploaded_by", nullable = false)
    private UUID uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "scanned_at")
    private Instant scannedAt;

    public static JustificationEvidence uploaded(UUID justificationId, String extension, String mediaType, long size,
                                                 String sha256, UUID uploadedBy, Instant now) {
        JustificationEvidence e = new JustificationEvidence();
        e.id = UuidV7.newId();
        e.justificationId = justificationId;
        e.objectKey = "evidencias/" + UuidV7.newId() + "-" + extension;
        e.mediaType = mediaType;
        e.sizeBytes = size;
        e.sha256 = sha256;
        e.scanStatus = ScanStatus.PENDIENTE;
        e.uploadedBy = uploadedBy;
        e.uploadedAt = now;
        return e;
    }

    public void scanned(ScanStatus result, Instant now) {
        this.scanStatus = result;
        this.scannedAt = now;
    }

    public boolean isAvailable() {
        return scanStatus == ScanStatus.LIMPIO;
    }

    /** Nombre neutro para la descarga (no se conserva el nombre original del archivo). */
    public String downloadName() {
        return "evidencia-" + id.toString().substring(0, 8) + objectKey.substring(objectKey.lastIndexOf('-')).replace('-', '.');
    }
}
