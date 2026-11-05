package co.edu.retencion.chat.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import co.edu.retencion.common.crypto.EncryptedStringConverter;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.id.UuidV7;

/** Reporte de un mensaje inapropiado (lo revisa ADMIN). */
@Entity
@Table(name = "chat_message_reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MessageReport {

    @Id
    private UUID id;

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "reporter_id", nullable = false)
    private UUID reporterId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "reason_ciphertext", nullable = false)
    private String reason;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static MessageReport of(UUID messageId, UUID reporter, String reason, Instant now) {
        if (reason == null || reason.isBlank() || reason.length() > 1000) {
            throw DomainException.badRequest("El motivo del reporte debe tener entre 1 y 1000 caracteres");
        }
        MessageReport r = new MessageReport();
        r.id = UuidV7.newId();
        r.messageId = messageId;
        r.reporterId = reporter;
        r.reason = reason.strip();
        r.status = "ABIERTO";
        r.createdAt = now;
        return r;
    }
}
