package co.edu.retencion.audit.application;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import co.edu.retencion.audit.AuditOutcome;
import co.edu.retencion.audit.AuditTrail;
import co.edu.retencion.audit.domain.AuditEntry;
import co.edu.retencion.audit.domain.AuditEventSink;
import co.edu.retencion.common.id.UuidV7;
import co.edu.retencion.common.security.AuthenticatedUser;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.common.web.ClientIpResolver;
import co.edu.retencion.common.web.RequestIdFilter;

/**
 * Construye las entradas de auditoría con el contexto de la petición (actor, IP, request-id,
 * motivo) y las envía al sink decorado.
 *
 * <p>Usa {@code REQUIRES_NEW}: el registro de un intento fallido debe quedar aunque la
 * transacción de negocio se revierta.
 */
@Service
public class AuditService implements AuditTrail {

    /** Cabecera con el motivo de acceso a datos sensibles. */
    public static final String REASON_HEADER = "X-Access-Reason";

    private final AuditEventSink sink;
    private final ClientIpResolver clientIpResolver;
    private final Clock clock;

    public AuditService(AuditEventSink sink, ClientIpResolver clientIpResolver, Clock clock) {
        this.sink = sink;
        this.clientIpResolver = clientIpResolver;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String entityType, String entityId, AuditOutcome outcome,
                       Map<String, String> details) {
        Optional<AuthenticatedUser> user = CurrentUser.get();
        write(user.map(AuthenticatedUser::userId).orElse(null), rolesOf(user), action, entityType, entityId,
                outcome, details);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAs(UUID actorUserId, String action, String entityType, String entityId,
                         AuditOutcome outcome, Map<String, String> details) {
        Optional<AuthenticatedUser> user = CurrentUser.get().filter(u -> u.userId().equals(actorUserId));
        write(actorUserId, rolesOf(user), action, entityType, entityId, outcome, details);
    }

    /** Motivo declarado por el usuario en la petición en curso, si existe. */
    public Optional<String> currentReason() {
        return currentRequest().map(r -> r.getHeader(REASON_HEADER)).filter(r -> !r.isBlank());
    }

    private void write(UUID actor, String roles, String action, String entityType, String entityId,
                       AuditOutcome outcome, Map<String, String> details) {
        Optional<HttpServletRequest> request = currentRequest();
        sink.append(AuditEntry.builder()
                .id(UuidV7.newId())
                .occurredAt(clock.instant())
                .actor(actor, roles)
                .action(action)
                .entity(entityType, entityId)
                .outcome(outcome)
                .ipAddress(request.map(clientIpResolver::resolve).orElse(null))
                .requestId(RequestIdFilter.current())
                .reason(currentReason().orElse(null))
                .details(details)
                .build());
    }

    private static String rolesOf(Optional<AuthenticatedUser> user) {
        return user.map(u -> u.roles().stream().map(Enum::name).sorted().collect(Collectors.joining(",")))
                .orElse(null);
    }

    private static Optional<HttpServletRequest> currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return Optional.of(attrs.getRequest());
        }
        return Optional.empty();
    }
}
