package co.edu.retencion.academic;

import java.util.UUID;

/**
 * Evento de dominio: cambió el docente de un grupo. En la Fase 3 las justificaciones pendientes
 * del grupo pasan al nuevo docente.
 *
 * @param groupId         grupo
 * @param previousTeacher docente anterior (puede ser {@code null})
 * @param newTeacher      docente nuevo
 */
public record GroupTeacherChanged(UUID groupId, UUID previousTeacher, UUID newTeacher) {
}
