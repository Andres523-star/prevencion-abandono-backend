package co.edu.retencion.records.domain;

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

/** Asociación estudiante-causa: abierta hasta que se resuelve. */
@Entity
@Table(name = "student_causes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudentCause {

    /** Cómo se identificó la causa. */
    public enum Source {
        AUTORREPORTE,
        ENTREVISTA,
        DETECCION
    }

    @Id
    private UUID id;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "cause_code", nullable = false)
    private String causeCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "notes_ciphertext")
    private String notes;

    @Column(name = "recorded_by")
    private UUID recordedBy;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public static StudentCause open(UUID studentId, String causeCode, Source source, String notes, UUID actor,
                                    Instant now) {
        StudentCause c = new StudentCause();
        c.id = UuidV7.newId();
        c.studentId = studentId;
        c.causeCode = causeCode;
        c.source = source;
        c.notes = notes == null || notes.isBlank() ? null : notes.trim();
        c.recordedBy = actor;
        c.recordedAt = now;
        return c;
    }

    public void resolve(Instant now) {
        if (resolvedAt != null) {
            throw DomainException.conflict("La causa ya estaba resuelta");
        }
        this.resolvedAt = now;
    }

    public boolean isOpen() {
        return resolvedAt == null;
    }
}
