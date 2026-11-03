package co.edu.retencion.academic.domain;

import co.edu.retencion.common.error.DomainException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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

/**
 * Sesión concreta de clase. Cada sesión genera registro de asistencia (Fase 3) y una ausencia
 * cuenta como UNA falta sin importar cuántas horas dure.
 */
@Entity
@Table(name = "class_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClassSession {

    /** Estado de la sesión. */
    public enum Status {
        PROGRAMADA,
        DICTADA,
        CANCELADA
    }

    @Id
    private UUID id;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "schedule_id")
    private UUID scheduleId;

    @Column(name = "session_date", nullable = false)
    private LocalDate date;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    private String room;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    /**
     * Transiciones permitidas: PROGRAMADA → DICTADA | CANCELADA y CANCELADA → PROGRAMADA
     * (reprogramar). Una sesión dictada ya no cambia: tiene asistencia asociada.
     */
    public void changeStatus(Status target, String reason, Instant now) {
        boolean allowed = switch (status) {
            case PROGRAMADA -> target == Status.DICTADA || target == Status.CANCELADA;
            case CANCELADA -> target == Status.PROGRAMADA;
            case DICTADA -> false;
        };
        if (!allowed) {
            throw DomainException.conflict("No se puede pasar una sesión de " + status + " a " + target);
        }
        if (target == Status.CANCELADA && (reason == null || reason.isBlank())) {
            throw DomainException.badRequest("Cancelar una sesión requiere un motivo");
        }
        this.status = target;
        this.cancellationReason = target == Status.CANCELADA ? reason : null;
        this.updatedAt = now;
    }
}
