package co.edu.retencion.records.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.id.UuidV7;
import co.edu.retencion.records.AttendanceStatus;
import co.edu.retencion.records.JustificationState;

/**
 * Asistencia de una inscripción a una sesión. Tabla particionada por periodo ({@code term_id}).
 * Una ausencia es UNA falta, sin importar cuántas horas dure la sesión.
 */
@Entity
@Table(name = "attendance_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttendanceRecord {

    @Id
    private UUID id;

    @Column(name = "term_id", nullable = false)
    private UUID termId;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "enrollment_id", nullable = false)
    private UUID enrollmentId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AttendanceStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "justification_status", nullable = false)
    private JustificationState justification;

    @Column(name = "justification_deadline")
    private LocalDate justificationDeadline;

    @Column(name = "recorded_by")
    private UUID recordedBy;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public static AttendanceRecord create(UUID termId, UUID sessionId, UUID enrollmentId, UUID studentId,
                                          UUID groupId, Instant now) {
        AttendanceRecord r = new AttendanceRecord();
        r.id = UuidV7.newId();
        r.termId = termId;
        r.sessionId = sessionId;
        r.enrollmentId = enrollmentId;
        r.studentId = studentId;
        r.groupId = groupId;
        r.justification = JustificationState.NINGUNA;
        r.recordedAt = now;
        r.updatedAt = now;
        return r;
    }

    /**
     * Registra o corrige la asistencia.
     *
     * @param deadline plazo para justificar (solo se usa si queda como FALTA)
     * @return {@code true} si el estado cambió
     */
    public boolean mark(AttendanceStatus newStatus, LocalDate deadline, UUID actor, Instant now) {
        if (newStatus == status) {
            return false;
        }
        if (justification == JustificationState.APROBADA || justification == JustificationState.RECHAZADA) {
            throw DomainException.conflict("La falta ya tiene una justificación revisada; no se puede cambiar");
        }
        this.status = newStatus;
        this.justification = JustificationState.NINGUNA;
        this.justificationDeadline = newStatus == AttendanceStatus.FALTA ? deadline : null;
        this.recordedBy = actor;
        this.updatedAt = now;
        return true;
    }

    public void startJustification(LocalDate today, Instant now) {
        requireAbsence();
        if (justification != JustificationState.NINGUNA) {
            throw DomainException.conflict("La falta ya tiene una justificación");
        }
        if (justificationDeadline != null && today.isAfter(justificationDeadline)) {
            throw DomainException.conflict("El plazo para justificar esta falta venció");
        }
        this.justification = JustificationState.PENDIENTE;
        this.updatedAt = now;
    }

    public void resolveJustification(boolean approved, Instant now) {
        requireAbsence();
        if (justification != JustificationState.PENDIENTE) {
            throw DomainException.conflict("La falta no tiene una justificación pendiente");
        }
        this.justification = approved ? JustificationState.APROBADA : JustificationState.RECHAZADA;
        this.updatedAt = now;
    }

    /** @return {@code true} si venció (falta sin justificar después del plazo) */
    public boolean expireIfOverdue(LocalDate today, Instant now) {
        if (status == AttendanceStatus.FALTA && justification == JustificationState.NINGUNA
                && justificationDeadline != null && today.isAfter(justificationDeadline)) {
            this.justification = JustificationState.VENCIDA;
            this.updatedAt = now;
            return true;
        }
        return false;
    }

    private void requireAbsence() {
        if (status != AttendanceStatus.FALTA) {
            throw DomainException.conflict("El registro no es una falta");
        }
    }
}
