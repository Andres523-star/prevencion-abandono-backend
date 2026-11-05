package co.edu.retencion.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import co.edu.retencion.common.ratelimit.RateLimitPolicy;
import co.edu.retencion.common.ratelimit.RateLimiter;
import co.edu.retencion.common.state.OneTimeTicketStore;
import co.edu.retencion.common.state.RedisStateFactory;
import co.edu.retencion.common.state.SharedCache;

/**
 * Familia Redis de la Abstract Factory contra un Redis real (Testcontainers). Se omite si no
 * hay Docker; en CI siempre corre.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisStateFactoryIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static RedisStateFactory factory;

    @BeforeAll
    static void connect() {
        factory = new RedisStateFactory(RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379)));
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    @Test
    void ticketsAreSingleUseAndNamespaced() {
        OneTimeTicketStore a = factory.createTicketStore("a");
        OneTimeTicketStore b = factory.createTicketStore("b");
        a.put("state", "valor", Duration.ofMinutes(1));
        assertThat(b.take("state")).isEmpty();
        assertThat(a.take("state")).contains("valor");
        assertThat(a.take("state")).isEmpty();
        assertThat(a.claim("jti", Duration.ofMinutes(1))).isTrue();
        assertThat(a.claim("jti", Duration.ofMinutes(1))).isFalse();
        assertThat(b.claim("jti", Duration.ofMinutes(1))).isTrue();
        assertThat(factory.ping()).isEqualToIgnoringCase("PONG");
    }

    @Test
    void cacheIsSharedAndInvalidatedByGeneration() {
        SharedCache one = factory.createCache("analitica");
        SharedCache other = factory.createCache("analitica");
        long generation = one.generation();
        one.put(generation + ":k", "v", Duration.ofMinutes(1));
        assertThat(other.get(generation + ":k")).contains("v");
        assertThat(factory.createCache("otro").get(generation + ":k")).isEmpty();
        assertThat(one.nextGeneration()).isEqualTo(generation + 1);
        assertThat(other.generation()).isEqualTo(generation + 1);
    }

    @Test
    void rateLimitIsSharedThroughRedis() {
        RateLimitPolicy policy = new RateLimitPolicy("redis-test", 2, Duration.ofMinutes(1));
        RateLimiter first = factory.createRateLimiter();
        RateLimiter second = factory.createRateLimiter(); // simula otra instancia del backend
        assertThat(first.tryConsume("ip:1", policy).allowed()).isTrue();
        assertThat(second.tryConsume("ip:1", policy).allowed()).isTrue();
        assertThat(first.tryConsume("ip:1", policy).allowed()).isFalse();
    }
}
