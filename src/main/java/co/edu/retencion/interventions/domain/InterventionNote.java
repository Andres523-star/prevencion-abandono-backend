package co.edu.retencion.interventions.domain;

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

/** Nota de seguimiento. {@code restricted} = solo la ve PSICOLOGO. */
@Entity
@Table(name = "intervention_notes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterventionNote {

    @Id
    private UUID id;

    @Column(name = "intervention_id", nullable = false)
    private UUID interventionId;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(nullable = false)
    private boolean restricted;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "text_ciphertext", nullable = false)
    private String text;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static InterventionNote write(UUID interventionId, UUID author, boolean restricted, String text, Instant now) {
        if (text == null || text.isBlank() || text.length() > 4000) {
            throw DomainException.badRequest("La nota debe tener entre 1 y 4000 caracteres");
        }
        InterventionNote n = new InterventionNote();
        n.id = UuidV7.newId();
        n.interventionId = interventionId;
        n.authorId = author;
        n.restricted = restricted;
        n.text = text.strip();
        n.createdAt = now;
        return n;
    }
}
