package co.edu.retencion.academic;

import java.util.UUID;

/**
 * Materia.
 *
 * @param id        id
 * @param programId programa
 * @param code      código
 * @param name      nombre
 */
public record SubjectInfo(UUID id, UUID programId, String code, String name) {
}
