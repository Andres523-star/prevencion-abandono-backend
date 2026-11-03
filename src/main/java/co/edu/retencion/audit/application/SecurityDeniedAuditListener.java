package co.edu.retencion.audit.application;

import java.util.Map;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import co.edu.retencion.audit.AuditOutcome;
import co.edu.retencion.common.security.SecurityDeniedEvent;

/**
 * Registra en la auditoría los accesos denegados (403) detectados por la capa de seguridad.
 */
@Component
class SecurityDeniedAuditListener {

    private final AuditService audit;

    SecurityDeniedAuditListener(AuditService audit) {
        this.audit = audit;
    }

    @EventListener
    void on(SecurityDeniedEvent event) {
        audit.recordAs(event.userId(), "ACCESS_DENIED", "HttpEndpoint",
                event.method() + " " + event.path(), AuditOutcome.DENIED, Map.of());
    }
}
