package co.edu.retencion.common.security.authorization;

import java.util.Optional;
import java.util.UUID;

/**
 * SPI: cada módulo dueño de un tipo de recurso publica un resolver que dice a qué programa,
 * grupo y titular pertenece un recurso. Así el ABAC es reutilizable sin que common conozca las
 * tablas de los demás módulos.
 */
public interface ResourceScopeResolver {

    /** Tipo de recurso que resuelve, p. ej. {@code USER}. */
    String resourceType();

    Optional<ResourceScope> resolve(UUID resourceId);
}
