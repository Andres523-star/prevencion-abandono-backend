package co.edu.retencion.justifications;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Evento: el docente aprobó o rechazó una justificación. El módulo de correo avisa al estudiante
 * (sin categoría, descripción ni comentario: pueden contener información de salud).
 *
 * @param justificationId justificación
 * @param recordId        registro de asistencia
 * @param enrollmentId    inscripción
 * @param studentId       estudiante
 * @param groupId         grupo
 * @param approved        aprobada o rechazada
 * @param subjectName     materia
 * @param sessionDate     fecha de la falta
 */
public record JustificationDecided(UUID justificationId, UUID recordId, UUID enrollmentId, UUID studentId,
                                   UUID groupId, boolean approved, String subjectName, LocalDate sessionDate) {
}
