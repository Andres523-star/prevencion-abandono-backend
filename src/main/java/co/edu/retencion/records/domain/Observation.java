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

import org.hibernate.annotations.SQLRestriction;

import co.edu.retencion.common.crypto.EncryptedStringConverter;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.id.UuidV7;

/**
 * Observación de un docente o tutor (académica o de conducta). El texto libre se cifra en columna
 * y nunca se envía a la IA sin pasar por el redactor de PII (Fase 4).
 */
@Entity
@Table(name = "observations")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Observation {

    /** Tipo de observación. */
    public enum Type {
        ACADEMICA,
        CONDUCTA
    }

    @Id
    private UUID id;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "group_id")
    private UUID groupId;

    @Column(name = "author_user_id", nullable = false)
    private UUID authorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "observation_type", nullable = false)
    private Type type;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "text_ciphertext", nullable = false)
    private String text;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public static Observation write(UUID studentId, UUID groupId, UUID author, Type type, String text, Instant now) {
        if (text == null || text.isBlank() || text.length() > 4000) {
            throw DomainException.badRequest("La observación debe tener entre 1 y 4000 caracteres");
        }
        Observation o = new Observation();
        o.id = UuidV7.newId();
        o.studentId = studentId;
        o.groupId = groupId;
        o.authorUserId = author;
        o.type = type;
        o.text = text.trim();
        o.createdAt = now;
        return o;
    }

    public void delete(UUID actor, Instant now) {
        if (!authorUserId.equals(actor)) {
            throw DomainException.conflict("Solo el autor puede eliminar su observación");
        }
        this.deletedAt = now;
    }
}
