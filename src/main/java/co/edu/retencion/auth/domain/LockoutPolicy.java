package co.edu.retencion.auth.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Bloqueo progresivo de cuentas locales: a partir de {@code maxAttempts} fallos consecutivos la
 * cuenta se bloquea {@code baseLock}, y cada fallo adicional duplica la duración hasta
 * {@code maxLock}. Ej. (3, 60 s, 24 h): 3.er fallo → 1 min, 4.º → 2 min, 5.º → 4 min...
 *
 * @param maxAttempts fallos permitidos antes del primer bloqueo
 * @param baseLock    duración del primer bloqueo
 * @param maxLock     duración máxima
 */
public record LockoutPolicy(int maxAttempts, Duration baseLock, Duration maxLock) {

    public LockoutPolicy {
        if (maxAttempts < 1 || baseLock.isNegative() || maxLock.compareTo(baseLock) < 0) {
            throw new IllegalArgumentException("Política de bloqueo inválida");
        }
    }

    /** Hasta cuándo queda bloqueada la cuenta tras {@code failedAttempts} fallos, si aplica. */
    public Optional<Instant> lockUntil(int failedAttempts, Instant now) {
        if (failedAttempts < maxAttempts) {
            return Optional.empty();
        }
        int exponent = Math.min(failedAttempts - maxAttempts, 30);
        long seconds = Math.min(baseLock.toSeconds() * (1L << exponent), maxLock.toSeconds());
        return Optional.of(now.plusSeconds(seconds));
    }
}
