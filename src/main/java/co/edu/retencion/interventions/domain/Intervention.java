package co.edu.retencion.interventions.domain;

import java.math.BigDecimal;
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
 * Intervención de apoyo. Ciclo PENDIENTE → EN_PROCESO → RESUELTA (con resultado). Guarda el riesgo
 * al crearla y el del reanálisis al resolverla, para medir su efecto.
 */
@Entity
@Table(name = "interventions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Intervention {

    /** Tipo de intervención. */
    public enum Type {
        TUTORIA,
        PSICOLOGIA,
        BECA,
        REUNION_PADRES,
        FLEXIBILIZACION,
        AYUDA_ECONOMICA,
        OTRO
    }

    /** Estado. */
    public enum Status {
        PENDIENTE,
        EN_PROCESO,
        RESUELTA
    }

    /** Foto del riesgo (antes o después). */
    public record RiskMark(UUID assessmentId, String level, BigDecimal score) {
    }

    @Id
    private UUID id;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private String title;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "description_ciphertext")
    private String description;

    @Column(name = "responsible_user_id")
    private UUID responsibleUserId;

    @Column(name = "responsible_role")
    private String responsibleRole;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "result_ciphertext")
    private String result;

    @Column(name = "alert_id")
    private UUID alertId;

    @Column(name = "plan_id")
    private UUID planId;

    @Column(name = "retention_case_id")
    private UUID retentionCaseId;

    @Column(name = "risk_before_assessment")
    private UUID riskBeforeAssessment;

    @Column(name = "risk_before_level")
    private String riskBeforeLevel;

    @Column(name = "risk_before_score")
    private BigDecimal riskBeforeScore;

    @Column(name = "risk_after_assessment")
    private UUID riskAfterAssessment;

    @Column(name = "risk_after_level")
    private String riskAfterLevel;

    @Column(name = "risk_after_score")
    private BigDecimal riskAfterScore;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Version
    private long version;

    /** Datos de alta. */
    public record Data(UUID studentId, Type type, String title, String description, UUID responsibleUserId,
                       String responsibleRole, LocalDate dueDate, UUID alertId, UUID planId, UUID retentionCaseId) {

        @Override
        public String toString() {
            return "Intervention.Data[" + type + "]";
        }
    }

    public static Intervention create(Data d, RiskMark before, UUID actor, Instant now) {
        if (d.title() == null || d.title().isBlank() || d.title().length() > 200) {
            throw DomainException.badRequest("El título debe tener entre 1 y 200 caracteres");
        }
        Intervention i = new Intervention();
        i.id = UuidV7.newId();
        i.studentId = d.studentId();
        i.type = d.type();
        i.status = Status.PENDIENTE;
        i.title = d.title().strip();
        i.description = d.description() == null || d.description().isBlank() ? null : d.description().strip();
        i.responsibleUserId = d.responsibleUserId();
        i.responsibleRole = d.responsibleRole();
        i.dueDate = d.dueDate();
        i.alertId = d.alertId();
        i.planId = d.planId();
        i.retentionCaseId = d.retentionCaseId();
        if (before != null) {
            i.riskBeforeAssessment = before.assessmentId();
            i.riskBeforeLevel = before.level();
            i.riskBeforeScore = before.score();
        }
        i.createdBy = actor;
        i.createdAt = now;
        i.updatedAt = now;
        return i;
    }

    public void start(Instant now) {
        if (status != Status.PENDIENTE) {
            throw DomainException.conflict("Solo una intervención PENDIENTE pasa a EN_PROCESO");
        }
        this.status = Status.EN_PROCESO;
        this.updatedAt = now;
    }

    public void resolve(String result, Instant now) {
        if (status == Status.RESUELTA) {
            throw DomainException.conflict("La intervención ya está resuelta");
        }
        if (result == null || result.isBlank()) {
            throw DomainException.badRequest("Resolver exige registrar el resultado");
        }
        this.status = Status.RESUELTA;
        this.result = result.strip();
        this.resolvedAt = now;
        this.updatedAt = now;
    }

    /** Riesgo del reanálisis posterior a la resolución. */
    public void measured(RiskMark after, Instant now) {
        this.riskAfterAssessment = after.assessmentId();
        this.riskAfterLevel = after.level();
        this.riskAfterScore = after.score();
        this.updatedAt = now;
    }

    /** Las notas de una intervención de psicología siempre son restringidas. */
    public boolean notesAlwaysRestricted() {
        return type == Type.PSICOLOGIA;
    }
}
