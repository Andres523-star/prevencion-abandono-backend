package co.edu.retencion.realtime.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.util.unit.DataSize;

import co.edu.retencion.common.ratelimit.Bucket4jLocalRateLimiter;
import co.edu.retencion.common.security.jwt.PlatformJwtAuthenticationConverter;
import co.edu.retencion.realtime.SubscriptionAuthorizer;

class StompAuthInterceptorTest {

    static final Instant NOW = Instant.parse("2026-11-05T15:00:00Z");
    final UUID user = UUID.randomUUID();
    final JwtDecoder decoder = mock(JwtDecoder.class);
    final StompAuthInterceptor interceptor = new StompAuthInterceptor(decoder, new PlatformJwtAuthenticationConverter(),
            List.of(new SubscriptionAuthorizer() {
                @Override
                public boolean supports(String destination) {
                    return destination.startsWith("/topic/chats.");
                }

                @Override
                public boolean canSubscribe(UUID userId, String destination) {
                    return destination.endsWith("permitido");
                }
            }), new Bucket4jLocalRateLimiter(),
            new RealtimeProperties("simple", "h", 1, "g", "g", "/", DataSize.ofKilobytes(64), Duration.ofSeconds(10), 2,
                    Duration.ofMinutes(1)), Clock.fixed(NOW, ZoneOffset.UTC));

    Message<byte[]> frame(StompCommand command, String destination, String authorization, Map<String, Object> session) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionAttributes(session);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (authorization != null) {
            accessor.addNativeHeader("Authorization", authorization);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    Map<String, Object> connected(Instant expiresAt) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(user.toString())
                .claim("sid", UUID.randomUUID().toString()).claim("roles", List.of("DOCENTE")).claim("mfa", false)
                .expiresAt(expiresAt).issuedAt(NOW.minusSeconds(60)).build();
        when(decoder.decode(anyString())).thenReturn(jwt);
        Map<String, Object> session = new HashMap<>();
        Message<byte[]> connect = frame(StompCommand.CONNECT, null, "Bearer t", session);
        interceptor.preSend(connect, null);
        assertThat(StompHeaderAccessor.wrap(connect).getUser()).isNotNull();
        return session;
    }

    @Test
    void connectRequiresAValidBearerTokenInTheStompHeader() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, null, null, new HashMap<>()), null))
                .isInstanceOf(MessageDeliveryException.class);
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, null, "Basic x", new HashMap<>()), null))
                .isInstanceOf(MessageDeliveryException.class);
        when(decoder.decode(anyString())).thenThrow(new BadJwtException("vencido"));
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, null, "Bearer x", new HashMap<>()), null))
                .isInstanceOf(MessageDeliveryException.class);
        // Sin CONNECT autenticado, nada más pasa.
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/user/queue/notifications", null,
                new HashMap<>()), null)).isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void subscriptionsOnlyToOwnQueueOrAuthorizedTopics() {
        Map<String, Object> session = connected(NOW.plusSeconds(600));
        assertThat(session).containsEntry(RealtimeSessions.USER, user);
        interceptor.preSend(withUser(frame(StompCommand.SUBSCRIBE, "/user/queue/notifications", null, session)), null);
        interceptor.preSend(withUser(frame(StompCommand.SUBSCRIBE, "/topic/chats.permitido", null, session)), null);
        for (String forbidden : List.of("/user/" + UUID.randomUUID() + "/queue/notifications", "/queue/notifications",
                "/topic/chats.ajeno", "/topic/otro", "/user/queue/../x")) {
            assertThatThrownBy(() -> interceptor.preSend(withUser(frame(StompCommand.SUBSCRIBE, forbidden, null, session)),
                    null)).as(forbidden).isInstanceOf(MessageDeliveryException.class);
        }
    }

    @Test
    void sendOnlyToAppDestinationsWithRateLimitAndExpiry() {
        Map<String, Object> session = connected(NOW.plusSeconds(600));
        assertThatThrownBy(() -> interceptor.preSend(withUser(frame(StompCommand.SEND, "/user/x/queue/chat", null, session)),
                null)).isInstanceOf(MessageDeliveryException.class);
        interceptor.preSend(withUser(frame(StompCommand.SEND, "/app/chat.send", null, session)), null);
        interceptor.preSend(withUser(frame(StompCommand.SEND, "/app/chat.send", null, session)), null);
        assertThatThrownBy(() -> interceptor.preSend(withUser(frame(StompCommand.SEND, "/app/chat.send", null, session)),
                null)).hasMessageContaining("Demasiados");
        Map<String, Object> expired = connected(NOW.minusSeconds(1));
        assertThatThrownBy(() -> interceptor.preSend(withUser(frame(StompCommand.SUBSCRIBE, "/user/queue/chat", null,
                expired)), null)).hasMessageContaining("vencido");
        interceptor.preSend(frame(StompCommand.DISCONNECT, null, null, expired), null);
    }

    private Message<byte[]> withUser(Message<byte[]> message) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        accessor.setUser(() -> user.toString());
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
    }
}
