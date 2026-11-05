package co.edu.retencion.realtime.infrastructure;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import co.edu.retencion.common.ratelimit.RateLimitPolicy;
import co.edu.retencion.common.ratelimit.RateLimiter;
import co.edu.retencion.common.security.PlatformAuthentication;
import co.edu.retencion.common.security.jwt.PlatformJwtAuthenticationConverter;
import co.edu.retencion.realtime.SubscriptionAuthorizer;

/**
 * Seguridad de los frames STOMP entrantes.
 *
 * <ul>
 *   <li>CONNECT: exige {@code Authorization: Bearer <jwt>} como cabecera STOMP (nunca query string),
 *       valida el token con el mismo decodificador de la API REST y asocia el usuario a la sesión.</li>
 *   <li>Cualquier otro frame: la sesión debe estar autenticada y el token vigente.</li>
 *   <li>SUBSCRIBE: solo {@code /user/queue/**} (la cola personal del propio usuario) o temas que un
 *       {@link SubscriptionAuthorizer} autorice. Todo lo demás se rechaza.</li>
 *   <li>SEND: solo {@code /app/**} y con rate limit por usuario.</li>
 * </ul>
 * Un rechazo lanza una excepción: Spring responde con un frame ERROR y cierra la conexión.
 */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthInterceptor.class);

    private final JwtDecoder decoder;
    private final PlatformJwtAuthenticationConverter converter;
    private final List<SubscriptionAuthorizer> authorizers;
    private final RateLimiter rateLimiter;
    private final RateLimitPolicy sendPolicy;
    private final Clock clock;

    public StompAuthInterceptor(JwtDecoder decoder, PlatformJwtAuthenticationConverter converter,
                                List<SubscriptionAuthorizer> authorizers, RateLimiter rateLimiter,
                                RealtimeProperties properties, Clock clock) {
        this.decoder = decoder;
        this.converter = converter;
        this.authorizers = List.copyOf(authorizers);
        this.rateLimiter = rateLimiter;
        this.sendPolicy = new RateLimitPolicy("ws-send", properties.sendCapacity(), properties.sendPeriod());
        this.clock = clock;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
            authenticate(accessor);
            return message;
        }
        if (command == StompCommand.DISCONNECT || command == StompCommand.UNSUBSCRIBE) {
            return message;
        }
        UUID user = requireLiveSession(accessor);
        String destination = accessor.getDestination();
        if (command == StompCommand.SUBSCRIBE) {
            authorizeSubscription(user, destination);
        } else if (command == StompCommand.SEND) {
            if (destination == null || !destination.startsWith("/app/")) {
                throw denied("Solo se puede enviar a /app/**");
            }
            if (!rateLimiter.tryConsume("ws:" + user, sendPolicy).allowed()) {
                throw denied("Demasiados mensajes; intenta en un momento");
            }
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw denied("CONNECT sin token");
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(header.substring(7).trim());
        } catch (JwtException ex) {
            throw denied("Token inválido o vencido");
        }
        PlatformAuthentication auth = (PlatformAuthentication) converter.convert(jwt);
        Map<String, Object> attributes = accessor.getSessionAttributes();
        if (attributes == null) {
            throw denied("Sesión sin atributos");
        }
        attributes.put(RealtimeSessions.USER, auth.getPrincipal().userId());
        attributes.put(RealtimeSessions.SESSION, auth.getPrincipal().sessionId());
        attributes.put(RealtimeSessions.EXPIRES, jwt.getExpiresAt());
        accessor.setUser(auth);
    }

    private UUID requireLiveSession(StompHeaderAccessor accessor) {
        Map<String, Object> attributes = accessor.getSessionAttributes();
        UUID user = RealtimeSessions.userOf(attributes);
        if (user == null || accessor.getUser() == null) {
            throw denied("Sesión no autenticada");
        }
        if (attributes.get(RealtimeSessions.EXPIRES) instanceof Instant exp && !exp.isAfter(clock.instant())) {
            throw denied("Token vencido");
        }
        return user;
    }

    private void authorizeSubscription(UUID user, String destination) {
        if (destination == null) {
            throw denied("Suscripción sin destino");
        }
        // Cola personal: Spring la resuelve a la sesión del propio usuario. Se rechaza el prefijo
        // /user/{otro}/... y cualquier cola o tema directo.
        if (destination.startsWith("/user/queue/") && !destination.contains("..")) {
            return;
        }
        for (SubscriptionAuthorizer authorizer : authorizers) {
            if (authorizer.supports(destination)) {
                if (authorizer.canSubscribe(user, destination)) {
                    return;
                }
                throw denied("Sin acceso a " + destination);
            }
        }
        throw denied("Destino no permitido: " + destination);
    }

    private static MessageDeliveryException denied(String reason) {
        log.info("Frame STOMP rechazado: {}", reason);
        return new MessageDeliveryException(reason);
    }
}
