package co.edu.retencion.records;

import java.util.UUID;

/**
 * API pública del catálogo de causas para el cierre de casos de retención (causa de retiro).
 */
public interface CauseRegistry {

    /** La causa existe y está activa. */
    boolean isActiveCause(String code);

    /** Registra la causa (origen ENTREVISTA) si el estudiante no la tiene abierta. */
    void recordExitCause(UUID studentId, String code, UUID actor);
}
