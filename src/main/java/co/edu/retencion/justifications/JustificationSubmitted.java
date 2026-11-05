package co.edu.retencion.justifications;

import java.util.UUID;

/**
 * Evento: el estudiante envió una justificación. La campanita avisa al docente del grupo, que es el
 * único que puede decidirla. Sin categoría ni descripción (pueden contener información de salud).
 *
 * @param justificationId justificación
 * @param studentId       estudiante
 * @param groupId         grupo
 * @param teacherUserId   docente asignado (puede ser {@code null})
 */
public record JustificationSubmitted(UUID justificationId, UUID studentId, UUID groupId, UUID teacherUserId) {
}
