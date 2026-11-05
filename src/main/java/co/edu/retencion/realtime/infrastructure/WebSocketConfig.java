package co.edu.retencion.realtime.infrastructure;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import co.edu.retencion.common.security.CorsConfig.CorsProperties;
import co.edu.retencion.common.security.PublicEndpoints;

/**
 * Endpoint {@code /ws} (SockJS como respaldo) con orígenes estrictos (los mismos de CORS, sin
 * comodines), prefijos {@code /app} (entrada) y {@code /user} (colas personales), límites de tamaño
 * y heartbeats. Broker simple o relay sobre RabbitMQ según {@code app.realtime.broker}.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
@EnableConfigurationProperties(RealtimeProperties.class)
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final RealtimeProperties properties;
    private final CorsProperties cors;
    private final StompAuthInterceptor authInterceptor;
    private final RealtimeSessions sessions;

    WebSocketConfig(RealtimeProperties properties, CorsProperties cors, StompAuthInterceptor authInterceptor,
                    RealtimeSessions sessions) {
        this.properties = properties;
        this.cors = cors;
        this.authInterceptor = authInterceptor;
        this.sessions = sessions;
    }

    /** El handshake HTTP es público: la autenticación ocurre en el frame CONNECT. */
    @Bean
    PublicEndpoints realtimePublicEndpoints() {
        return new PublicEndpoints(List.of("/ws", "/ws/**"));
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] origins = cors.allowedOrigins().toArray(String[]::new);
        registry.addEndpoint("/ws").setAllowedOrigins(origins).withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        long beat = properties.heartbeat().toMillis();
        if (properties.relay()) {
            registry.enableStompBrokerRelay("/queue", "/topic")
                    .setRelayHost(properties.relayHost()).setRelayPort(properties.relayPort())
                    .setClientLogin(properties.relayLogin()).setClientPasscode(properties.relayPasscode())
                    .setSystemLogin(properties.relayLogin()).setSystemPasscode(properties.relayPasscode())
                    .setVirtualHost(properties.relayVirtualHost())
                    .setSystemHeartbeatSendInterval(beat).setSystemHeartbeatReceiveInterval(beat)
                    // Varias instancias: el registro de usuarios y los mensajes sin resolver se
                    // difunden por el broker para llegar a la instancia que tiene la conexión.
                    .setUserDestinationBroadcast("/topic/unresolved-user")
                    .setUserRegistryBroadcast("/topic/user-registry");
        } else {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("ws-heartbeat-");
            scheduler.initialize();
            registry.enableSimpleBroker("/queue", "/topic").setHeartbeatValue(new long[] {beat, beat})
                    .setTaskScheduler(scheduler);
        }
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        int limit = (int) properties.messageSizeLimit().toBytes();
        registration.setMessageSizeLimit(limit)
                .setSendBufferSizeLimit(512 * 1024)
                .setSendTimeLimit(15_000)
                .addDecoratorFactory(sessions);
    }
}
