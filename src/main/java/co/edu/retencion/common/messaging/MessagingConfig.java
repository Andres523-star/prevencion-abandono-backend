package co.edu.retencion.common.messaging;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Externalización opcional de eventos de dominio a RabbitMQ ({@code APP_RABBIT_ENABLED=true}).
 * Spring Modulith publica los eventos marcados con {@code @Externalized} en el exchange
 * {@code retencion.events} después de confirmar la transacción (outbox de su registro de
 * publicaciones). Desactivado por defecto: en Render no hay RabbitMQ gestionado y los módulos se
 * comunican igual dentro del monolito.
 *
 * <p>Asistido por IA (Claude, Anthropic).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.rabbit.enabled", havingValue = "true")
public class MessagingConfig {

    public static final String EVENTS_EXCHANGE = "retencion.events";

    @Bean
    TopicExchange retencionEventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false);
    }
}
