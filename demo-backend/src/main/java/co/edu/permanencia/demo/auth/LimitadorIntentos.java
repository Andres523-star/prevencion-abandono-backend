package co.edu.permanencia.demo.auth;

import co.edu.permanencia.demo.common.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limitador de intentos por clave (IP o correo) con ventana deslizante en memoria.
 *
 * <p>Se usa para frenar la adivinación de contraseñas y el registro masivo de cuentas, como el
 * rate limit estricto de {@code /api/v1/auth/} del backend principal. Solo cuentan los intentos
 * fallidos de login, así que un salón entero entrando con las cuentas de la demo desde la misma IP
 * no se bloquea. Vive en memoria porque la demo corre en una sola instancia.</p>
 */
@Component
public class LimitadorIntentos {

    /** Por encima de este número de claves se descartan las ventanas ya vencidas. */
    private static final int LIMPIEZA_UMBRAL = 10_000;

    private final Map<String, Deque<Instant>> intentos = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public LimitadorIntentos() {
        this(Clock.systemUTC());
    }

    LimitadorIntentos(Clock clock) {
        this.clock = clock;
    }

    /**
     * Rechaza con 429 si la clave ya acumuló {@code maximo} intentos dentro de la ventana.
     */
    public void verificar(String clave, int maximo, Duration ventana, String mensaje) {
        Deque<Instant> cola = intentos.get(clave);
        if (cola == null) {
            return;
        }
        Instant ahora = clock.instant();
        synchronized (cola) {
            podar(cola, ahora, ventana);
            if (cola.size() >= maximo) {
                long espera = Duration.between(ahora, cola.peekFirst().plus(ventana)).toSeconds();
                throw ApiException.tooManyRequests(mensaje, espera);
            }
        }
    }

    /** Anota un intento para la clave. */
    public void registrar(String clave, Duration ventana) {
        Instant ahora = clock.instant();
        if (intentos.size() > LIMPIEZA_UMBRAL) {
            intentos.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    podar(e.getValue(), ahora, ventana);
                    return e.getValue().isEmpty();
                }
            });
        }
        Deque<Instant> cola = intentos.computeIfAbsent(clave, k -> new ArrayDeque<>());
        synchronized (cola) {
            podar(cola, ahora, ventana);
            cola.addLast(ahora);
        }
    }

    /** Borra el historial de la clave (por ejemplo, tras un login correcto). */
    public void limpiar(String clave) {
        intentos.remove(clave);
    }

    private static void podar(Deque<Instant> cola, Instant ahora, Duration ventana) {
        Instant limite = ahora.minus(ventana);
        while (!cola.isEmpty() && !cola.peekFirst().isAfter(limite)) {
            cola.pollFirst();
        }
    }
}
