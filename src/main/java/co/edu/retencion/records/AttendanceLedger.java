package co.edu.retencion.records;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * API pública de la asistencia para el módulo de justificaciones y el de correo. Cada cambio de
 * estado de justificación recalcula el contador en la misma transacción y emite los eventos de
 * umbral que correspondan.
 */
public interface AttendanceLedger {

    Optional<AbsenceInfo> findRecord(UUID recordId);

    Optional<CounterInfo> counter(UUID enrollmentId);

    /** El estudiante envió una justificación: la falta queda PENDIENTE (sigue contando). */
    void markJustificationPending(UUID recordId);

    /** Apelación: la justificación rechazada vuelve a PENDIENTE hasta la nueva decisión del docente. */
    void reopenJustification(UUID recordId);

    /** Decisión del docente: APROBADA (se descuenta) o RECHAZADA (cuenta como injustificada). */
    CounterInfo resolveJustification(UUID recordId, boolean approved);

    /**
     * Faltas sin justificación cuyo plazo terminó antes de {@code today}: pasan a VENCIDA.
     *
     * @return cantidad de faltas vencidas
     */
    int expireOverdue(LocalDate today);
}
