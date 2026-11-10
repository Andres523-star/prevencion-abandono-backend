package co.edu.retencion.privacy.domain;

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
import jakarta.persistence.Version;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import co.edu.retencion.common.crypto.EncryptedStringConverter;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.id.UuidV7;

/**
 * Solicitud del titular sobre sus datos. RADICADA → APROBADA → EJECUTADA, o RECHAZADA (con motivo).
 *
 * <p>Una consulta (ACCESO) se ejecuta al aprobarla: se entrega la copia. La rectificación se ejecuta cuando
 * el personal corrigió el dato. La supresión exige <b>dos personas</b>: quien la ejecuta no puede ser quien
 * la aprobó, porque borrar datos personales no tiene vuelta atrás.
 */
@Entity
@Table(name = "data_subject_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DataSubjectRequest {

    /** Derecho que ejerce el titular. */
    public enum Type {
        ACCESO,
        RECTIFICACION,
        SUPRESION;

        /** Consulta: 10 días hábiles; reclamo (rectificación o supresión): 15. */
        public boolean isClaim() {
            return this != ACCESO;
        }
    }

    /** Estado. */
    public enum Status {
        RADICADA,
        APROBADA,
        RECHAZADA,
        EJECUTADA
    }

    @Id
    private UUID id;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false)
    private Type type;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "detail_ciphertext")
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "due_on", nullable = false)
    private LocalDate dueOn;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "decision_comment_ciphertext")
    private String decisionComment;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "executed_by")
    private UUID executedBy;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "export_object_key")
    private String exportObjectKey;

    @Column(name = "export_expires_at")
    private Instant exportExpiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public static DataSubjectRequest submit(UUID studentId, UUID requestedBy, Type type, String detail, LocalDate dueOn,
                                            Instant now) {
        if (type == Type.RECTIFICACION && (detail == null || detail.isBlank())) {
            throw DomainException.badRequest("Indica qué dato hay que corregir y cuál es el correcto");
        }
        DataSubjectRequest r = new DataSubjectRequest();
        r.id = UuidV7.newId();
        r.studentId = studentId;
        r.requestedBy = requestedBy;
        r.type = type;
        r.detail = detail == null || detail.isBlank() ? null : detail.trim();
        r.status = Status.RADICADA;
        r.dueOn = dueOn;
        r.createdAt = now;
        r.updatedAt = now;
        return r;
    }

    public void approve(UUID reviewer, String comment, Instant now) {
        requireStatus(Status.RADICADA);
        this.status = Status.APROBADA;
        this.decidedBy = reviewer;
        this.decisionComment = comment == null || comment.isBlank() ? null : comment.trim();
        this.decidedAt = now;
        this.updatedAt = now;
    }

    public void reject(UUID reviewer, String comment, Instant now) {
        requireStatus(Status.RADICADA);
        if (comment == null || comment.isBlank()) {
            throw DomainException.badRequest("Rechazar una solicitud exige explicar el motivo al titular");
        }
        this.status = Status.RECHAZADA;
        this.decidedBy = reviewer;
        this.decisionComment = comment.trim();
        this.decidedAt = now;
        this.updatedAt = now;
    }

    /**
     * @throws DomainException si no está aprobada o si es una supresión y la ejecuta quien la aprobó
     */
    public void execute(UUID executor, String note, Instant now) {
        requireStatus(Status.APROBADA);
        if (type == Type.SUPRESION && executor.equals(decidedBy)) {
            throw DomainException.conflict("La supresión la ejecuta una persona distinta de quien la aprobó");
        }
        if (type == Type.RECTIFICACION && (note == null || note.isBlank())) {
            throw DomainException.badRequest("Describe la corrección realizada");
        }
        this.status = Status.EJECUTADA;
        this.executedBy = executor;
        if (note != null && !note.isBlank()) {
            this.decisionComment = decisionComment == null ? note.trim() : decisionComment + "\n" + note.trim();
        }
        this.executedAt = now;
        this.updatedAt = now;
    }

    /** Copia de los datos lista para el titular. */
    public void attachExport(String key, Instant expiresAt, Instant now) {
        if (type != Type.ACCESO) {
            throw DomainException.conflict("Solo una consulta entrega copia de los datos");
        }
        this.exportObjectKey = key;
        this.exportExpiresAt = expiresAt;
        this.updatedAt = now;
    }

    public void expireExport(Instant now) {
        this.exportObjectKey = null;
        this.updatedAt = now;
    }

    public boolean exportAvailable(Instant now) {
        return exportObjectKey != null && exportExpiresAt != null && exportExpiresAt.isAfter(now);
    }

    public boolean isOverdue(LocalDate today) {
        return (status == Status.RADICADA || status == Status.APROBADA) && today.isAfter(dueOn);
    }

    private void requireStatus(Status expected) {
        if (status != expected) {
            throw DomainException.conflict("La solicitud está " + status + "; se esperaba " + expected);
        }
    }

    @Override
    public String toString() {
        return "DataSubjectRequest[" + id + ", " + type + ", " + status + "]";
    }
}
