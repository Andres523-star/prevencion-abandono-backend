package co.edu.retencion.records;

import java.util.UUID;

import org.springframework.modulith.events.Externalized;

/**
 * Evento: una inscripción alcanzó un umbral de asistencia (AVISO o CRÍTICO). Lo consumirán el
 * motor de riesgo (Fase 4) y las alertas (Fase 5). Sin datos personales.
 *
 * @param enrollmentId    inscripción
 * @param studentId       estudiante
 * @param groupId         grupo
 * @param level           nivel alcanzado
 * @param countedAbsences faltas que cuentan
 * @param maxAbsences     límite de la política
 */
@Externalized("retencion.events::attendance.threshold-reached")
public record AttendanceThresholdReached(UUID enrollmentId, UUID studentId, UUID groupId, AttendanceLevel level,
                                         int countedAbsences, int maxAbsences) {
}
