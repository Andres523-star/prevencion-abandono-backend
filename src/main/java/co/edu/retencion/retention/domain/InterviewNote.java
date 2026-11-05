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

/** Nota de entrevista (restringida: consejero asignado y PSICOLOGO; cifrada). */
@Entity
@Table(name = "retention_interview_notes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterviewNote {

    @Id
    private UUID id;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "text_ciphertext", nullable = false)
    private String text;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;


    public static InterviewNote write(UUID caseId, UUID author, String text, Instant now) {
        if (text == null || text.isBlank() || text.length() > 8000) {
            throw DomainException.badRequest("La nota debe tener entre 1 y 8000 caracteres");
        }
        InterviewNote n = new InterviewNote();
        n.id = UuidV7.newId();
        n.caseId = caseId;
        n.authorId = author;
        n.text = text.strip();
        n.createdAt = now;
        return n;
    }

}
