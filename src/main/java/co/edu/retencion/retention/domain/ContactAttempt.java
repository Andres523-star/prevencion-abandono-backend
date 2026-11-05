package co.edu.retencion.retention.domain;

import java.time.Instant;
import java.time.LocalDate;
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

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import co.edu.retencion.common.crypto.EncryptedStringConverter;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.id.UuidV7;

/** Intento de contacto con el estudiante (las notas se cifran). */
@Entity
@Table(name = "retention_contacts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContactAttempt {

    /** Canal. */
    public enum Channel {
        TELEFONO,
        CORREO,
        CHAT,
        PRESENCIAL,
        OTRO
    }

    /** Resultado. */
    public enum Result {
        CONTACTADO,
        NO_CONTESTA,
        RECHAZA,
        OTRO
    }

    @Id
    private UUID id;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Result result;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "notes_ciphertext")
    private String notes;

    @Column(name = "attempted_by", nullable = false)
    private UUID attemptedBy;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;


    public static ContactAttempt of(UUID caseId, Channel channel, Result result, String notes, UUID actor, Instant now) {
        ContactAttempt c = new ContactAttempt();
        c.id = UuidV7.newId();
        c.caseId = caseId;
        c.channel = channel;
        c.result = result;
        c.notes = notes == null || notes.isBlank() ? null : notes.strip();
        c.attemptedBy = actor;
        c.attemptedAt = now;
        return c;
    }

}
