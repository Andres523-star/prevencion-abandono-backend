package co.edu.retencion.common.security.authorization;

import java.util.Set;
import java.util.UUID;

/**
 * Atributos de alcance de un recurso concreto, usados por el ABAC: a qué programa(s) y
 * grupo(s) pertenece y quién es su titular.
 *
 * @param type        tipo de recurso, p. ej. {@code USER}, {@code STUDENT}
 * @param id          id del recurso
 * @param programIds  programas a los que pertenece
 * @param groupIds    grupos (materia + periodo) a los que pertenece
 * @param ownerUserId usuario titular del dato (el propio estudiante), si aplica
 */
public record ResourceScope(String type, UUID id, Set<UUID> programIds, Set<UUID> groupIds, UUID ownerUserId) {

    public ResourceScope {
        programIds = Set.copyOf(programIds);
        groupIds = Set.copyOf(groupIds);
    }
}
