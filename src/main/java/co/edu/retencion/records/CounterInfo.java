package co.edu.retencion.records;

import java.util.UUID;

/**
 * Contador de faltas de una inscripción.
 *
 * @param enrollmentId    inscripción
 * @param groupId         grupo
 * @param unjustified     faltas sin justificar, rechazadas o vencidas
 * @param pending         faltas con justificación pendiente de revisión (aún cuentan)
 * @param justified       faltas justificadas y aprobadas por el docente (no cuentan)
 * @param tardies         tardanzas
 * @param countedAbsences faltas que cuentan para la pérdida: injustificadas + pendientes + equivalencia de tardanzas
 * @param maxAbsences     límite de la política aplicable
 * @param level           nivel alcanzado
 */
public record CounterInfo(UUID enrollmentId, UUID groupId, int unjustified, int pending, int justified, int tardies,
                          int countedAbsences, int maxAbsences, AttendanceLevel level) {
}
