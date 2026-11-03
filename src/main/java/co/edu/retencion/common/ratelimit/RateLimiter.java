package co.edu.retencion.common.ratelimit;

/**
 * Puerto de rate limiting independiente de la librería. Las implementaciones son adaptadores
 * sobre Bucket4j (local o Redis).
 */
public interface RateLimiter {

    /** Intenta consumir un permiso del cubo identificado por {@code key} con la política dada. */
    RateLimitDecision tryConsume(String key, RateLimitPolicy policy);
}
