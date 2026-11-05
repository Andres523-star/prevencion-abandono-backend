package co.edu.retencion.realtime.infrastructure;

import java.util.UUID;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import co.edu.retencion.realtime.RealtimePublisher;

/**
 * {@link RealtimePublisher} sobre el broker STOMP (simple o relay).
 *
 * <p><b>Patrón Adapter:</b> adapta {@code SimpMessagingTemplate} de Spring al puerto propio
 * {@link RealtimePublisher}; los módulos de negocio no conocen STOMP ni el tipo de broker.
 */
@Component
class StompRealtimePublisher implements RealtimePublisher {

    private final SimpMessagingTemplate template;

    StompRealtimePublisher(SimpMessagingTemplate template) {
        this.template = template;
    }

    @Override
    public void toUser(UUID userId, String queue, Object payload) {
        template.convertAndSendToUser(userId.toString(), "/queue/" + queue, payload);
    }

    @Override
    public void toTopic(String topic, Object payload) {
        template.convertAndSend("/topic/" + topic, payload);
    }
}
