package co.edu.retencion.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import co.edu.retencion.common.error.ApiProblem;
import co.edu.retencion.common.error.ProblemCatalog;
import co.edu.retencion.common.id.UuidV7;
import co.edu.retencion.common.state.InMemoryStateFactory;
import co.edu.retencion.common.state.OneTimeTicketStore;

/**
 * Pruebas de los patrones creacionales de common: Singleton (UuidV7), Prototype (ApiProblem /
 * ProblemCatalog) y Abstract Factory (familia en memoria).
 */
class CreationalPatternsTest {

    @Test
    void uuidV7IsVersion7MonotonicAndUnique() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(UuidV7.newId());
        }
        assertThat(ids).allSatisfy(id -> {
            assertThat(id.version()).isEqualTo(7);
            assertThat(id.variant()).isEqualTo(2);
        });
        assertThat(new HashSet<>(ids)).hasSize(ids.size());
        // Ordenables en el tiempo: generar en secuencia produce orden creciente (comparación sin signo).
        for (int i = 1; i < ids.size(); i++) {
            assertThat(Long.compareUnsigned(ids.get(i).getMostSignificantBits(), ids.get(i - 1).getMostSignificantBits()))
                    .isPositive();
        }
        assertThat(UuidV7.INSTANCE).isSameAs(UuidV7.valueOf("INSTANCE"));
    }

    @Test
    void prototypeCopiesAreIndependent() {
        ProblemCatalog catalog = new ProblemCatalog(List.of(() -> List.of(
                new ApiProblem("demo", HttpStatus.CONFLICT, "Demo"))));
        ApiProblem first = catalog.create("demo").detail("uno").property("k", 1);
        ApiProblem second = catalog.create("demo");

        assertThat(first.detail()).isEqualTo("uno");
        assertThat(second.detail()).isNull();
        assertThat(second.toProblemDetail(null).getProperties()).doesNotContainKey("k");
        assertThat(second.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(catalog.create("no-existe").code()).isEqualTo(ProblemCatalog.INTERNAL_ERROR);
    }

    @Test
    void duplicateProblemCodesFailFast() {
        assertThatThrownBy(() -> new ProblemCatalog(List.of(() -> List.of(
                new ApiProblem(ProblemCatalog.NOT_FOUND, HttpStatus.NOT_FOUND, "x")))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void inMemoryFamilyTicketsAreSingleUse() {
        OneTimeTicketStore store = new InMemoryStateFactory().createTicketStore("test");
        store.put("state", "valor", java.time.Duration.ofMinutes(1));
        assertThat(store.take("state")).contains("valor");
        assertThat(store.take("state")).isEmpty();
        assertThat(store.claim("token", java.time.Duration.ofMinutes(1))).isTrue();
        assertThat(store.claim("token", java.time.Duration.ofMinutes(1))).isFalse();
    }

    @Test
    void inMemoryFamilyRateLimiterEnforcesCapacity() {
        var limiter = new InMemoryStateFactory().createRateLimiter();
        var policy = new co.edu.retencion.common.ratelimit.RateLimitPolicy("t", 2, java.time.Duration.ofMinutes(1));
        assertThat(limiter.tryConsume("k", policy).allowed()).isTrue();
        assertThat(limiter.tryConsume("k", policy).allowed()).isTrue();
        var denied = limiter.tryConsume("k", policy);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfter()).isPositive();
        assertThat(limiter.tryConsume("otra", policy).allowed()).isTrue();
    }
}
