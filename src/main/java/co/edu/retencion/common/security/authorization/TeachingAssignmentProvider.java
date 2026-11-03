package co.edu.retencion.common.security.authorization;

import java.util.Set;
import java.util.UUID;

/**
 * SPI: grupos que dicta un docente. En la Fase 1 no hay estructura académica, así que la
 * implementación por defecto devuelve vacío; el módulo academic-structure (Fase 2) la sustituye.
 */
public interface TeachingAssignmentProvider {

    Set<UUID> groupsTaughtBy(UUID teacherUserId);
}
