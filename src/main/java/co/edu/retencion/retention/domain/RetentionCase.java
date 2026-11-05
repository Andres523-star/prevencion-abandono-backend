package co.edu.retencion.retention.domain;

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

import co.edu.retencion.common.crypto.EncryptedStringConverter;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.id.UuidV7;

/**
 * Caso de retención. Las etapas solo avanzan de una en una (sin saltos ni retrocesos). El cierre
 * RETENIDO exige haber llegado a SEGUIMIENTO; APLAZADO, TRANSFERIDO y RETIRADO se pueden registrar
 * en cualquier etapa abierta (el estudiante puede irse antes). RETIRADO y TRANSFERIDO exigen causa
 * del catálogo; RETIRADO, además, encuesta de salida.
 */
@Entity
@Table(name = "retention_cases")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RetentionCase {

    /** Etapa. El orden importa. */
    public enum Stage {
        DETECTADO,
        CONTACTADO,
        ENTREVISTA,
        PLAN_DE_APOYO,
        SEGUIMIENTO,
        CERRADO;

        Stage next() {
            return this == CERRADO ? null : values()[ordinal() + 1];
        }
    }

    /** Cómo se abrió. */
    public enum Origin {
        AUTOMATICO,
        AUTORREPORTE,
        CONSEJERO
    }

    /** Resultado del cierre. */
    public enum Outcome {
        RETENIDO,
        APLAZADO,
        TRANSFERIDO,
        RETIRADO
    }

    @Id
    private UUID id;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "program_id", nullable = false)
    private UUID programId;

    @Column(name = "term_id")
    private UUID termId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Stage stage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Origin origin;

    @Column(name = "assigned_counselor_id")
    private UUID assignedCounselorId;

    @Enumerated(EnumType.STRING)
    private Outcome outcome;

    @Column(name = "exit_cause_code")
    private String exitCauseCode;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "exit_survey_ciphertext")
    private String exitSurvey;

    @Column(name = "opened_by")
    private UUID openedBy;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "contacted_at")
    private Instant contactedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public static RetentionCase open(UUID studentId, UUID programId, UUID termId, Origin origin, UUID counselor,
                                     UUID openedBy, Instant now) {
        RetentionCase c = new RetentionCase();
        c.id = UuidV7.newId();
        c.studentId = studentId;
        c.programId = programId;
        c.termId = termId;
        c.stage = Stage.DETECTADO;
        c.origin = origin;
        c.assignedCounselorId = counselor;
        c.openedBy = openedBy;
        c.openedAt = now;
        c.updatedAt = now;
        return c;
    }

    /**
     * Avanza a la etapa siguiente.
     *
     * @param target           etapa pedida (debe ser exactamente la siguiente)
     * @param reachedContact   hubo un intento de contacto exitoso (requisito de CONTACTADO)
     * @param hasPlanTasks     el plan tiene tareas (requisito de SEGUIMIENTO)
     */
    public void advanceTo(Stage target, boolean reachedContact, boolean hasPlanTasks, Instant now) {
        if (stage == Stage.CERRADO) {
            throw DomainException.conflict("El caso está cerrado");
        }
        if (target == Stage.CERRADO) {
            throw DomainException.badRequest("Para cerrar el caso use el cierre con resultado");
        }
        if (target != stage.next()) {
            throw DomainException.conflict("Transición inválida: " + stage + " → " + target
                    + " (la siguiente etapa es " + stage.next() + ")");
        }
        if (target == Stage.CONTACTADO && !reachedContact) {
            throw DomainException.conflict("Registre primero un intento de contacto exitoso");
        }
        if (target == Stage.SEGUIMIENTO && !hasPlanTasks) {
            throw DomainException.conflict("El plan de apoyo necesita al menos una tarea con fecha");
        }
        if (target == Stage.CONTACTADO) {
            this.contactedAt = now;
        }
        this.stage = target;
        this.updatedAt = now;
    }

    public void close(Outcome result, String causeCode, String surveyJson, Instant now) {
        if (stage == Stage.CERRADO) {
            throw DomainException.conflict("El caso ya está cerrado");
        }
        if (result == Outcome.RETENIDO && stage != Stage.SEGUIMIENTO) {
            throw DomainException.conflict("Solo se cierra como RETENIDO después del seguimiento");
        }
        boolean needsCause = result == Outcome.RETIRADO || result == Outcome.TRANSFERIDO;
        if (needsCause && (causeCode == null || causeCode.isBlank())) {
            throw DomainException.badRequest("El cierre " + result + " exige la causa del catálogo");
        }
        if (result == Outcome.RETIRADO && (surveyJson == null || surveyJson.isBlank() || "{}".equals(surveyJson))) {
            throw DomainException.badRequest("El retiro exige la encuesta de salida");
        }
        this.stage = Stage.CERRADO;
        this.outcome = result;
        this.exitCauseCode = needsCause ? causeCode : null;
        this.exitSurvey = surveyJson;
        this.closedAt = now;
        this.updatedAt = now;
    }

    public void assign(UUID counselor, Instant now) {
        this.assignedCounselorId = counselor;
        this.updatedAt = now;
    }

    public boolean isOpen() {
        return stage != Stage.CERRADO;
    }
}
