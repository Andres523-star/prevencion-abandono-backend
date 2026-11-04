package co.edu.retencion.mailing.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Destinatario que rebotó, identificado por el blind index de su correo (nunca la dirección). Al
 * rectificar el correo del estudiante cambia el blind index y la supresión deja de aplicar.
 */
@Entity
@Table(name = "email_suppressions")
public class EmailSuppression {

    @Id
    @Column(name = "recipient_index", length = 64)
    private String recipientIndex;

    @Column(nullable = false, length = 100)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected EmailSuppression() {
    }

    public EmailSuppression(String recipientIndex, String reason, Instant now) {
        this.recipientIndex = recipientIndex;
        this.reason = reason == null || reason.isBlank() ? "rebote"
                : reason.length() > 100 ? reason.substring(0, 100) : reason;
        this.createdAt = now;
    }

    public String getRecipientIndex() {
        return recipientIndex;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
