package co.edu.retencion.justifications.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
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
 * Justificación de una falta. La descripción y el comentario del docente se cifran: pueden contener
 * información de salud (dato sensible). Solo el docente asignado (el del grupo) decide.
 */
@Entity
@Table(name = "absence_justifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AbsenceJustification {

    /** Motivo declarado. */
    public enum Category {
        SALUD,
        FAMILIAR,
        LABORAL,
        TRANSPORTE,
        OTRO
    }

    /** Estado de la justificación. */
    public enum Status {
        PENDIENTE,
        APROBADA,
        RECHAZADA,
        VENCIDA
    }

    @Id
    private UUID id;

    @Column(name = "attendance_record_id", nullable = false)
    private UUID attendanceRecordId;

    @Column(name = "term_id", nullable = false)
    private UUID termId;

    @Column(name = "enrollment_id", nullable = false)
    private UUID enrollmentId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "description_ciphertext", nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private LocalDate deadline;

    @Column(name = "assigned_teacher_id")
    private UUID assignedTeacherId;

    @Column(name = "reviewer_user_id")
    private UUID reviewerUserId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "review_comment_ciphertext")
    private String reviewComment;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public static AbsenceJustification submit(UUID recordId, UUID termId, UUID enrollmentId, UUID studentId,
                                              UUID groupId, Category category, String description, LocalDate deadline,
                                              UUID teacher, Instant now) {
        if (description == null || description.isBlank() || description.length() > 2000) {
            throw DomainException.badRequest("La descripción debe tener entre 1 y 2000 caracteres");
        }
        AbsenceJustification j = new AbsenceJustification();
        j.id = UuidV7.newId();
        j.attendanceRecordId = recordId;
        j.termId = termId;
        j.enrollmentId = enrollmentId;
        j.studentId = studentId;
        j.groupId = groupId;
        j.category = Objects.requireNonNull(category, "category");
        j.description = description.trim();
        j.deadline = deadline;
        j.assignedTeacherId = teacher;
        j.status = Status.PENDIENTE;
        j.submittedAt = now;
        j.updatedAt = now;
        return j;
    }

    /**
     * Decisión del docente. Rechazar exige comentario.
     *
     * @throws DomainException si no está pendiente o falta el comentario de rechazo
     */
    public void decide(boolean approved, UUID reviewer, String comment, Instant now) {
        if (status != Status.PENDIENTE) {
            throw DomainException.conflict("La justificación ya fue revisada");
        }
        if (!approved && (comment == null || comment.isBlank())) {
            throw DomainException.badRequest("Rechazar una justificación requiere un comentario");
        }
        this.status = approved ? Status.APROBADA : Status.RECHAZADA;
        this.reviewerUserId = reviewer;
        this.reviewComment = comment == null || comment.isBlank() ? null : comment.trim();
        this.reviewedAt = now;
        this.updatedAt = now;
    }

    /** Las pendientes pasan al nuevo docente si el grupo cambia de docente. */
    public void reassign(UUID teacher, Instant now) {
        if (status == Status.PENDIENTE) {
            this.assignedTeacherId = teacher;
            this.updatedAt = now;
        }
    }

    public boolean isAssignedTo(UUID teacher) {
        return assignedTeacherId != null && assignedTeacherId.equals(teacher);
    }

    @Override
    public String toString() {
        return "AbsenceJustification[id=" + id + ", status=" + status + "]";
    }
}
