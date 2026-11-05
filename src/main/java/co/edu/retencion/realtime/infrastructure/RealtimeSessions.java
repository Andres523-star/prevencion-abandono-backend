package co.edu.retencion.realtime.infrastructure;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

import co.edu.retencion.common.security.SessionRevoked;

/**
 * Registro de conexiones WebSocket abiertas. Cierra las conexiones cuyo access token venció (revisión
 * periódica) y las de una sesión revocada (logout, reutilización de refresh token, cuenta
 * desactivada) apenas se confirma la revocación, sin esperar al vencimiento del token.
 *
 * <p>Los atributos ({@link #USER}, {@link #SESSION}, {@link #EXPIRES}) los pone
 * {@link StompAuthInterceptor} en el CONNECT; son los mismos atributos de la sesión WebSocket.
 *
 * <p><b>Patrón Decorator:</b> como {@code WebSocketHandlerDecoratorFactory} envuelve el manejador
 * STOMP de Spring para registrar y olvidar cada conexión, sin modificar el manejador original.
 */
@Component
public class RealtimeSessions implements WebSocketHandlerDecoratorFactory {

    static final String USER = "rt.user";
    static final String SESSION = "rt.session";
    static final String EXPIRES = "rt.expires";

    private static final Logger log = LoggerFactory.getLogger(RealtimeSessions.class);

    private final Map<String, WebSocketSession> open = new ConcurrentHashMap<>();
    private final Clock clock;

    public RealtimeSessions(Clock clock) {
        this.clock = clock;
    }

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                open.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                open.remove(session.getId());
                super.afterConnectionClosed(session, status);
            }
        };
    }

    /** Conexiones abiertas (métrica y pruebas). */
    public int openCount() {
        return open.size();
    }

    @Scheduled(fixedDelayString = "${app.realtime.expiry-check-ms:15000}")
    public void closeExpired() {
        Instant now = clock.instant();
        closeWhere(s -> s.getAttributes().get(EXPIRES) instanceof Instant exp && !exp.isAfter(now), "token vencido");
    }

    @TransactionalEventListener(fallbackExecution = true)
    void on(SessionRevoked event) {
        closeWhere(s -> event.userId().equals(s.getAttributes().get(USER))
                && (event.sessionId() == null || event.sessionId().equals(s.getAttributes().get(SESSION))), "sesión revocada");
    }

    private void closeWhere(Predicate<WebSocketSession> condition, String reason) {
        open.values().stream().filter(condition).forEach(s -> {
            try {
                s.close(CloseStatus.POLICY_VIOLATION.withReason(reason));
            } catch (IOException ex) {
                log.debug("No se pudo cerrar la conexión WebSocket: {}", ex.getClass().getSimpleName());
            }
            open.remove(s.getId());
        });
    }

    static UUID userOf(Map<String, Object> attributes) {
        return attributes == null ? null : (UUID) attributes.get(USER);
    }
}
