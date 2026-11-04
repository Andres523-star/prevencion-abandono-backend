package co.edu.retencion.mailing.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import co.edu.retencion.common.id.UuidV7;

/**
 * Fila del outbox de correo y, a la vez, registro de entrega. No guarda la dirección del
 * destinatario (se resuelve al enviar); solo su blind index para asociar rebotes.
 */
@Entity
@Table(name = "email_outbox")
public class EmailOutbox {

    public enum Status { PENDIENTE, ENVIANDO, ENVIADO, FALLIDO, CANCELADO, REBOTADO, SUPRIMIDO }

    private static final int MAX_ERROR = 300;

    @Id
    private UUID id;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private MailTemplate template;

    @Column(name = "dedupe_key", nullable = false, unique = true, length = 150)
    private String dedupeKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload = new HashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status;

    @Column(name = "not_before", nullable = false)
    private Instant notBefore;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = MAX_ERROR)
    private String lastError;

    @Column(name = "provider_message_id", length = 200)
    private String providerMessageId;

    @Column(name = "recipient_index", length = 64)
    private String recipientIndex;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Version
    private long version;

    protected EmailOutbox() {
    }

    public static EmailOutbox schedule(UUID studentId, MailTemplate template, String dedupeKey,
                                       Map<String, Object> payload, Instant notBefore, Instant now) {
        EmailOutbox row = new EmailOutbox();
        row.id = UuidV7.newId();
        row.studentId = studentId;
        row.template = template;
        row.dedupeKey = dedupeKey;
        row.payload = new HashMap<>(payload);
        row.status = Status.PENDIENTE;
        row.notBefore = notBefore;
        row.nextAttemptAt = notBefore;
        row.createdAt = now;
        return row;
    }

    /** Vuelve a programar un aviso aún no enviado (p. ej. la falta se re-marcó tras corregirse). */
    public boolean reschedule(Map<String, Object> newPayload, Instant notBefore) {
        if (status != Status.PENDIENTE && status != Status.CANCELADO) {
            return false;
        }
        payload = new HashMap<>(newPayload);
        status = Status.PENDIENTE;
        this.notBefore = notBefore;
        nextAttemptAt = notBefore;
        attempts = 0;
        lastError = null;
        return true;
    }

    public boolean cancel(String reason) {
        if (status != Status.PENDIENTE && status != Status.ENVIANDO) {
            return false;
        }
        status = Status.CANCELADO;
        lastError = truncate(reason);
        return true;
    }

    public void sent(MailTemplate finalTemplate, String recipientIndex, String providerMessageId, Instant now) {
        template = finalTemplate;
        this.recipientIndex = recipientIndex;
        this.providerMessageId = providerMessageId;
        status = Status.ENVIADO;
        attempts++;
        sentAt = now;
        lastError = null;
    }

    /**
     * Fallo de envío: reintenta con backoff exponencial hasta {@code maxAttempts}; un error
     * permanente (dirección inválida, rechazo del proveedor) no se reintenta.
     */
    public void failed(String reason, boolean permanent, int maxAttempts, Duration backoffBase, Duration backoffMax,
                       Instant now) {
        attempts++;
        lastError = truncate(reason);
        if (permanent || attempts >= maxAttempts) {
            status = Status.FALLIDO;
            return;
        }
        long factor = 1L << Math.min(attempts - 1, 20);
        Duration delay = backoffBase.multipliedBy(factor);
        status = Status.PENDIENTE;
        nextAttemptAt = now.plus(delay.compareTo(backoffMax) > 0 ? backoffMax : delay);
    }

    public void suppressed(String recipientIndex) {
        this.recipientIndex = recipientIndex;
        status = Status.SUPRIMIDO;
        lastError = "Destinatario suprimido por rebote previo";
    }

    public void bounced(String reason) {
        status = Status.REBOTADO;
        lastError = truncate(reason);
    }

    public void noRecipient() {
        status = Status.FALLIDO;
        attempts++;
        lastError = "El estudiante no tiene correo de contacto";
    }

    public boolean isDigest() {
        return Boolean.TRUE.equals(payload.get("digest"));
    }

    public String payloadString(String key) {
        Object value = payload.get(key);
        return value == null ? null : value.toString();
    }

    public UUID payloadUuid(String key) {
        String value = payloadString(key);
        return value == null ? null : UUID.fromString(value);
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= MAX_ERROR ? reason : reason.substring(0, MAX_ERROR);
    }

    public UUID getId() {
        return id;
    }

    public UUID getStudentId() {
        return studentId;
    }

    public MailTemplate getTemplate() {
        return template;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }

    public Map<String, Object> getPayload() {
        return java.util.Collections.unmodifiableMap(payload);
    }

    public Status getStatus() {
        return status;
    }

    public Instant getNotBefore() {
        return notBefore;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public String getProviderMessageId() {
        return providerMessageId;
    }

    public String getRecipientIndex() {
        return recipientIndex;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
