package co.edu.retencion.common.ratelimit;

import java.time.Duration;

/**
 * Resultado de intentar consumir un permiso.
 *
 * @param allowed    si la petición puede continuar
 * @param remaining  permisos restantes en el cubo
 * @param retryAfter tiempo de espera sugerido cuando se rechaza
 */
public record RateLimitDecision(boolean allowed, long remaining, Duration retryAfter) {
}
