package co.edu.retencion.risk.domain;

import java.time.Instant;
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

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import co.edu.retencion.common.crypto.EncryptedStringConverter;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.id.UuidV7;
import co.edu.retencion.records.AttendanceLevel;

/**
 * Plan de apoyo para que el estudiante no pierda una materia por inasistencia. Nace PROPUESTO y
 * solo un humano lo APRUEBA o RECHAZA (Fase 5: lo convierte en intervenciones). Un plan nuevo para la
 * misma inscripción reemplaza al propuesto anterior.
 */
@Entity
@Table(name = "support_plans")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SupportPlan {

    /** Estado del plan. */
    public enum Status {
        PROPUESTO,
        APROBADO,
        RECHAZADO,
        REEMPLAZADO
    }

    /** Quién redactó el plan. */
    public enum Source {
        RULES,
        AI
    }

    @Id
    private UUID id;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "enrollment_id", nullable = false)
    private UUID enrollmentId;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_level", nullable = false)
    private AttendanceLevel triggerLevel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String actions;

    @Column(name = "student_message", nullable = false)
    private String studentMessage;

    @Column(name = "ai_model")
    private String aiModel;

    @Column(name = "prompt_version")
    private String promptVersion;

    @Column(name = "payload_hash")
    private String payloadHash;

    @Column(name = "fallback_reason")
    private String fallbackReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "review_comment_ciphertext")
    private String reviewComment;

    @Version
    private long version;

    /**
     * @param aiModel        modelo que lo redactó ({@code null} si es de reglas)
     * @param fallbackReason por qué no lo redactó la IA ({@code null} si lo hizo)
     */
    public static SupportPlan propose(UUID studentId, UUID enrollmentId, UUID groupId, AttendanceLevel level,
                                      Source source, String actionsJson, String studentMessage, String aiModel,
                                      String promptVersion, String payloadHash, String fallbackReason, Instant now) {
        if (level == AttendanceLevel.NINGUNO) {
            throw new IllegalArgumentException("Un plan de apoyo necesita un umbral alcanzado");
        }
        SupportPlan p = new SupportPlan();
        p.id = UuidV7.newId();
        p.studentId = studentId;
        p.enrollmentId = enrollmentId;
        p.groupId = groupId;
        p.triggerLevel = level;
        p.status = Status.PROPUESTO;
        p.source = source;
        p.actions = actionsJson;
        p.studentMessage = studentMessage;
        p.aiModel = aiModel;
        p.promptVersion = promptVersion;
        p.payloadHash = payloadHash;
        p.fallbackReason = fallbackReason;
        p.createdAt = now;
        return p;
    }

    /** El revisor editó las acciones antes de aprobar (solo mientras está PROPUESTO). */
    public void replaceActions(String actionsJson) {
        if (status != Status.PROPUESTO) {
            throw DomainException.conflict("El plan ya fue revisado o reemplazado");
        }
        this.actions = actionsJson;
    }

    public void approve(UUID reviewer, String comment, Instant now) {
        review(Status.APROBADO, reviewer, comment, now);
    }

    public void reject(UUID reviewer, String comment, Instant now) {
        if (comment == null || comment.isBlank()) {
            throw DomainException.badRequest("Rechazar un plan exige un comentario");
        }
        review(Status.RECHAZADO, reviewer, comment, now);
    }

    public void supersede(Instant now) {
        if (status == Status.PROPUESTO) {
            this.status = Status.REEMPLAZADO;
            this.reviewedAt = now;
        }
    }

    private void review(Status target, UUID reviewer, String comment, Instant now) {
        if (status != Status.PROPUESTO) {
            throw DomainException.conflict("El plan ya fue revisado o reemplazado");
        }
        this.status = target;
        this.reviewedBy = reviewer;
        this.reviewedAt = now;
        this.reviewComment = comment == null || comment.isBlank() ? null : comment.trim();
    }
}
