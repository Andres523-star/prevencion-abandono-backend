package co.edu.retencion.support;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Espera a que terminen los listeners de eventos de dominio.
 *
 * <p>Spring Modulith habilita {@code @EnableAsync}: cada {@code @ApplicationModuleListener} corre en
 * otro hilo después de confirmar la transacción que publicó el evento. Sin esperar, una prueba puede
 * leer antes de que el listener escriba (correo encolado, cola de riesgo, alerta, campanita) y fallar
 * de forma intermitente. Como el registro de publicaciones borra las completadas
 * ({@code completion-mode: delete}), basta con esperar a que no quede ninguna pendiente; una cadena de
 * eventos siempre deja al menos una publicación pendiente mientras avanza.
 *
 * <p>Una publicación que sigue pendiente al vencer la espera es un listener que falló (Modulith la
 * deja sin completar y lo registra en INFO): se recuerda para no volver a esperarla.
 */
public class EventListenerAwaiter {

    private static final Logger log = LoggerFactory.getLogger(EventListenerAwaiter.class);
    private static final Duration WAIT = Duration.ofSeconds(15);

    private final JdbcTemplate jdbc;
    private final Set<String> failed = ConcurrentHashMap.newKeySet();

    public EventListenerAwaiter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Espera a que terminen los listeners en curso. */
    public void await() {
        long deadline = System.nanoTime() + WAIT.toNanos();
        List<String> pending = pending();
        while (!pending.isEmpty()) {
            if (System.nanoTime() > deadline) {
                log.warn("Listeners sin completar tras {} s (fallaron): {}", WAIT.toSeconds(), pending);
                failed.addAll(pending);
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            pending = pending();
        }
    }

    private List<String> pending() {
        List<String> ids = jdbc.queryForList(
                "SELECT id::text FROM event_publication WHERE completion_date IS NULL", String.class);
        ids.removeIf(failed::contains);
        return ids;
    }
}
