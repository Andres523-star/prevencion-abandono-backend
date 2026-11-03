package co.edu.retencion.common.state;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import co.edu.retencion.common.ratelimit.RateLimiter;

/**
 * Selecciona la fábrica abstracta según {@code app.state.backend} y expone sus productos.
 */
@Configuration(proxyBeanMethods = false)
public class StateConfig {

    /**
     * @param backend  {@code memory} (una instancia) o {@code redis} (producción)
     * @param redisUrl URL de Redis, p. ej. la {@code connectionString} de Render Key Value
     */
    @ConfigurationProperties("app.state")
    public record StateProperties(@DefaultValue("memory") String backend, String redisUrl) {
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "app.state.backend", havingValue = "redis")
    RedisStateFactory redisStateFactory(StateProperties properties) {
        if (properties.redisUrl() == null || properties.redisUrl().isBlank()) {
            throw new IllegalStateException("app.state.backend=redis requiere REDIS_URL");
        }
        return new RedisStateFactory(RedisClient.create(RedisURI.create(properties.redisUrl())));
    }

    @Bean
    @ConditionalOnProperty(name = "app.state.backend", havingValue = "memory", matchIfMissing = true)
    InMemoryStateFactory inMemoryStateFactory() {
        return new InMemoryStateFactory();
    }

    @Bean
    RateLimiter rateLimiter(DistributedStateFactory factory) {
        return factory.createRateLimiter();
    }

    @Bean
    @ConditionalOnProperty(name = "app.state.backend", havingValue = "redis")
    HealthIndicator redisHealthIndicator(RedisStateFactory factory) {
        return () -> {
            try {
                return "PONG".equalsIgnoreCase(factory.ping()) ? Health.up().build() : Health.down().build();
            } catch (RuntimeException ex) {
                return Health.down().withDetail("error", ex.getClass().getSimpleName()).build();
            }
        };
    }
}
