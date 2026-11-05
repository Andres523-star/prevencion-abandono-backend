package co.edu.retencion.realtime.infrastructure;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Configuración del tiempo real ({@code app.realtime}).
 *
 * @param broker           {@code simple} (una instancia: dev/pruebas) o {@code relay} (RabbitMQ con plugin STOMP)
 * @param relayHost        host del broker STOMP
 * @param relayPort        puerto STOMP (61613)
 * @param relayLogin       usuario del broker
 * @param relayPasscode    clave del broker
 * @param relayVirtualHost virtual host de RabbitMQ
 * @param messageSizeLimit tamaño máximo de un frame entrante
 * @param heartbeat        intervalo de heartbeats servidor/cliente
 * @param sendCapacity     frames SEND permitidos por usuario en {@code sendPeriod}
 * @param sendPeriod       periodo del rate limit
 */
@ConfigurationProperties(prefix = "app.realtime")
public record RealtimeProperties(@DefaultValue("simple") String broker, @DefaultValue("localhost") String relayHost,
                                 @DefaultValue("61613") int relayPort, @DefaultValue("guest") String relayLogin,
                                 @DefaultValue("guest") String relayPasscode, @DefaultValue("/") String relayVirtualHost,
                                 @DefaultValue("64KB") DataSize messageSizeLimit,
                                 @DefaultValue("10s") Duration heartbeat, @DefaultValue("60") long sendCapacity,
                                 @DefaultValue("1m") Duration sendPeriod) {

    public boolean relay() {
        return "relay".equalsIgnoreCase(broker);
    }

    @Override
    public String toString() {
        return "RealtimeProperties[broker=" + broker + "]";
    }
}
