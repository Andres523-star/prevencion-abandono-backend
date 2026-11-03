package co.edu.retencion.common.state;

import java.time.Duration;
import java.util.Optional;

/**
 * Almacén de "tickets" de un solo uso con vencimiento. Operaciones atómicas incluso con varias
 * instancias (en Redis):
 * <ul>
 *   <li>{@link #take(String)} lee y borra: un ticket no se puede consumir dos veces.</li>
 *   <li>{@link #claim(String, Duration)} marca una clave como usada solo si nadie lo hizo antes
 *       (anti-replay, p. ej. de tokens de Supabase ya canjeados).</li>
 * </ul>
 */
public interface OneTimeTicketStore {

    void put(String key, String value, Duration ttl);

    Optional<String> take(String key);

    /** @return {@code true} si esta llamada fue la primera en reclamar la clave */
    boolean claim(String key, Duration ttl);
}
