package co.edu.retencion.audit;

import java.util.Map;
import java.util.UUID;

/**
 * Registro programático de eventos de auditoría para los casos que no cubre una anotación
 * (p. ej. detección de reutilización de refresh token). Los detalles NUNCA deben contener
 * datos personales; igualmente pasan por un decorador que los depura.
 */
public interface AuditTrail {

    /** Registra con el usuario de la petición en curso como actor. */
    void record(String action, String entityType, String entityId, AuditOutcome outcome, Map<String, String> details);

    /** Registra con un actor explícito (p. ej. el usuario que acaba de iniciar sesión). */
    void recordAs(UUID actorUserId, String action, String entityType, String entityId,
                  AuditOutcome outcome, Map<String, String> details);
}
