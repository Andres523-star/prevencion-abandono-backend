package co.edu.retencion.students;

import java.util.UUID;

/**
 * Vista pública mínima de un estudiante para otros módulos. Sin identidad.
 *
 * @param id              id interno
 * @param anonCode        código seudónimo
 * @param programId       programa
 * @param currentSemester semestre actual
 * @param status          estado
 * @param userId          usuario vinculado (portal), puede ser {@code null}
 */
public record StudentInfo(UUID id, String anonCode, UUID programId, int currentSemester, StudentStatus status,
                          UUID userId) {
}
