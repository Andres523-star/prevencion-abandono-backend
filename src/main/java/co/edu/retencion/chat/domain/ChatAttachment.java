package co.edu.retencion.chat.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import co.edu.retencion.common.crypto.EncryptedStringConverter;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.id.UuidV7;

/**
 * Adjunto del chat. Se sube antes de enviar el mensaje; solo un adjunto LIMPIO (antivirus) se puede
 * enviar, y una sola vez. Nombre original saneado y cifrado; clave de objeto aleatoria.
 */
@Entity
@Table(name = "chat_attachments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatAttachment {

    /** Estado del escaneo antivirus. */
    public enum ScanStatus {
        PENDIENTE,
        LIMPIO,
        INFECTADO,
        ERROR
    }

    @Id
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "uploader_id", nullable = false)
    private UUID uploaderId;

    @Column(name = "object_key", nullable = false, unique = true)
    private String objectKey;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "original_name_ciphertext", nullable = false)
    private String originalName;

    @Column(name = "media_type", nullable = false)
    private String mediaType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(nullable = false)
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "scan_status", nullable = false)
    private ScanStatus scanStatus;

    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public static ChatAttachment uploaded(UUID conversationId, UUID uploader, String safeName, String extension,
                                          String mediaType, long size, Integer durationSeconds, String sha256,
                                          ScanStatus scan, Instant now) {
        ChatAttachment a = new ChatAttachment();
        a.id = UuidV7.newId();
        a.conversationId = conversationId;
        a.uploaderId = uploader;
        a.objectKey = "chat/" + UuidV7.newId() + "-" + extension;
        a.originalName = safeName;
        a.mediaType = mediaType;
        a.sizeBytes = size;
        a.durationSeconds = durationSeconds;
        a.sha256 = sha256;
        a.scanStatus = scan;
        a.createdAt = now;
        return a;
    }

    /** Lo usa el mensaje que lo envía (una sola vez y solo si está limpio). */
    public void attachTo(UUID messageId, UUID sender, UUID conversation) {
        if (!uploaderId.equals(sender) || !conversationId.equals(conversation)) {
            throw DomainException.badRequest("El adjunto no pertenece a esta conversación");
        }
        if (scanStatus != ScanStatus.LIMPIO) {
            throw DomainException.conflict("El adjunto aún no pasó el antivirus");
        }
        if (this.messageId != null || deletedAt != null) {
            throw DomainException.conflict("El adjunto ya se envió");
        }
        this.messageId = messageId;
    }

    public void delete(Instant now) {
        this.deletedAt = now;
    }

    public boolean isAudio() {
        return mediaType.startsWith("audio/");
    }
}
