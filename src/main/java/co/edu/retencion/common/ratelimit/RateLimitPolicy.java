package co.edu.retencion.common.ratelimit;

import java.time.Duration;

/**
 * Política de cubo de tokens: {@code capacity} peticiones que se recargan por completo cada
 * {@code period}.
 *
 * @param name     nombre de la política (forma parte de la clave del cubo)
 * @param capacity peticiones permitidas por periodo
 * @param period   periodo de recarga
 */
public record RateLimitPolicy(String name, long capacity, Duration period) {

    public RateLimitPolicy {
        if (capacity < 1 || period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("Política de rate limit inválida: " + name);
        }
    }
}
