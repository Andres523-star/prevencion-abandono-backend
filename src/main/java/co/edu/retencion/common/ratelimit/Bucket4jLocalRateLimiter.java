package co.edu.retencion.common.ratelimit;

import java.time.Duration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;

/**
 * <b>Patrón estructural: Adapter.</b> Adapta la API de Bucket4j (cubos en memoria) a nuestra
 * interfaz {@link RateLimiter}. Útil en desarrollo y tests; con varias instancias en producción
 * se usa {@link Bucket4jRedisRateLimiter} para compartir los contadores.
 *
 * <p>Los cubos se guardan en una caché acotada que expira por inactividad para no crecer sin
 * límite con IPs distintas.
 */
public class Bucket4jLocalRateLimiter implements RateLimiter {

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofMinutes(30))
            .build();

    @Override
    public RateLimitDecision tryConsume(String key, RateLimitPolicy policy) {
        Bucket bucket = buckets.get(policy.name() + "|" + key, k -> newBucket(policy));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        return new RateLimitDecision(probe.isConsumed(), probe.getRemainingTokens(),
                Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }

    static Bandwidth bandwidth(RateLimitPolicy policy) {
        return Bandwidth.builder()
                .capacity(policy.capacity())
                .refillGreedy(policy.capacity(), policy.period())
                .build();
    }

    private static Bucket newBucket(RateLimitPolicy policy) {
        return Bucket.builder().addLimit(bandwidth(policy)).build();
    }
}
