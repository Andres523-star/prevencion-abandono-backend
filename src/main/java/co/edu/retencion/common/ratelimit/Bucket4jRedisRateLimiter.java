package co.edu.retencion.common.ratelimit;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;

/**
 * <b>Patrón estructural: Adapter.</b> Adapta Bucket4j con almacenamiento en Redis (Lettuce,
 * compare-and-swap) a nuestra interfaz {@link RateLimiter}. Los contadores se comparten entre
 * todas las instancias del backend.
 */
public class Bucket4jRedisRateLimiter implements RateLimiter {

    private static final String KEY_PREFIX = "rl:";

    private final ProxyManager<byte[]> proxyManager;

    public Bucket4jRedisRateLimiter(RedisClient redisClient) {
        this.proxyManager = Bucket4jLettuce.casBasedBuilder(redisClient)
                .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(5)))
                .build();
    }

    @Override
    public RateLimitDecision tryConsume(String key, RateLimitPolicy policy) {
        byte[] redisKey = (KEY_PREFIX + policy.name() + ":" + key).getBytes(StandardCharsets.UTF_8);
        BucketConfiguration configuration = BucketConfiguration.builder()
                .addLimit(Bucket4jLocalRateLimiter.bandwidth(policy))
                .build();
        ConsumptionProbe probe = proxyManager.builder().build(redisKey, () -> configuration)
                .tryConsumeAndReturnRemaining(1);
        return new RateLimitDecision(probe.isConsumed(), probe.getRemainingTokens(),
                Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }
}
