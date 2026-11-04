package co.edu.retencion.records;

import java.util.UUID;

import org.springframework.modulith.events.Externalized;

/**
 * Evento: el estudiante alcanzó el máximo de faltas de la política (pérdida por inasistencia).
 *
 * @param enrollmentId    inscripción
 * @param studentId       estudiante
 * @param groupId         grupo
 * @param countedAbsences faltas que cuentan
 * @param maxAbsences     límite de la política
 */
@Externalized("retencion.events::attendance.subject-lost")
public record SubjectLostByAbsences(UUID enrollmentId, UUID studentId, UUID groupId, int countedAbsences,
                                    int maxAbsences) {
}
