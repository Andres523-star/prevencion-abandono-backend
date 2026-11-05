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
 * Mensaje del chat. El texto se cifra en columna. Eliminar es lógico (el registro queda para la
 * auditoría); la retención configurable borra el contenido ({@code purgedAt}).
 */
@Entity
@Table(name = "chat_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {

    /** Tipo de mensaje. */
    public enum Type {
        TEXTO,
        AUDIO,
        DOCUMENTO
    }

    @Id
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false)
    private Type type;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "text_ciphertext")
    private String text;

    @Column(name = "attachment_id")
    private UUID attachmentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "edited_at")
    private Instant editedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "purged_at")
    private Instant purgedAt;

    public static ChatMessage send(UUID conversationId, UUID sender, Type type, String text, UUID attachmentId,
                                   int maxLength, Instant now) {
        String body = text == null || text.isBlank() ? null : text.strip();
        if (type == Type.TEXTO && body == null) {
            throw DomainException.badRequest("El mensaje está vacío");
        }
        if (body != null && body.length() > maxLength) {
            throw DomainException.badRequest("El mensaje supera " + maxLength + " caracteres");
        }
        if (type != Type.TEXTO && attachmentId == null) {
            throw DomainException.badRequest("Falta el adjunto");
        }
        ChatMessage m = new ChatMessage();
        m.id = UuidV7.newId();
        m.conversationId = conversationId;
        m.senderId = sender;
        m.type = type;
        m.text = body;
        m.attachmentId = attachmentId;
        m.createdAt = now;
        return m;
    }

    public void deleteBy(UUID actor, Instant now) {
        if (!senderId.equals(actor)) {
            throw DomainException.conflict("Solo puedes eliminar tus propios mensajes");
        }
        if (deletedAt == null) {
            this.deletedAt = now;
        }
    }

    /** Retención vencida: se borra el contenido y queda solo el registro. */
    public void purge(Instant now) {
        this.text = null;
        this.purgedAt = now;
    }

    public boolean visible() {
        return deletedAt == null && purgedAt == null;
    }
}
