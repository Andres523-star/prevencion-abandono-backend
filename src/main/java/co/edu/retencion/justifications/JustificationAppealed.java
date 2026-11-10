package co.edu.retencion.justifications;

import java.util.UUID;

/** El estudiante apeló una justificación rechazada; la decide de nuevo el docente de la materia. */
public record JustificationAppealed(UUID justificationId, UUID studentId, UUID groupId, UUID teacherUserId) {
}
