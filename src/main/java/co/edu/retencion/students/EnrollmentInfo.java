package co.edu.retencion.students;

import java.util.UUID;

/**
 * Inscripción de un estudiante en un grupo.
 *
 * @param id        id
 * @param studentId estudiante
 * @param groupId   grupo
 * @param termId    periodo
 * @param active    {@code false} si el estudiante se retiró de la materia
 */
public record EnrollmentInfo(UUID id, UUID studentId, UUID groupId, UUID termId, boolean active) {
}
