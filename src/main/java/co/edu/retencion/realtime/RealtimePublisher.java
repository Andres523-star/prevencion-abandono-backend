package co.edu.retencion.realtime;

import java.util.UUID;

/**
 * Entrega mensajes en tiempo real a la cola personal de un usuario ({@code /user/queue/<cola>}).
 * Si el usuario no está conectado, el mensaje se pierde: la fuente de verdad es siempre la base
 * de datos (la campanita y el chat se consultan también por REST).
 */
public interface RealtimePublisher {

    /** @param queue nombre de la cola, p. ej. {@code notifications} o {@code chat} */
    void toUser(UUID userId, String queue, Object payload);

    /**
     * Publica en un tema ({@code /topic/<tema>}); solo lo reciben quienes un
     * {@link SubscriptionAuthorizer} dejó suscribirse.
     */
    void toTopic(String topic, Object payload);
}
