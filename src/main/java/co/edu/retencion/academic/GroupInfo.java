package co.edu.retencion.academic;

import java.util.UUID;

/**
 * Grupo (materia + periodo + docente + cupo) con el programa al que pertenece su materia.
 *
 * @param id            id del grupo
 * @param subjectId     materia
 * @param programId     programa de la materia
 * @param termId        periodo
 * @param code          código del grupo dentro de la materia y el periodo
 * @param teacherUserId docente (usuario), puede ser {@code null} si aún no se asigna
 * @param capacity      cupo
 * @param groupType     regular o práctica clínica
 */
public record GroupInfo(UUID id, UUID subjectId, UUID programId, UUID termId, String code, UUID teacherUserId,
                        int capacity, GroupType groupType) {
}
