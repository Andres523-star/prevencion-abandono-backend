package co.edu.retencion.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import co.edu.retencion.audit.domain.AuditEntry;
import co.edu.retencion.audit.domain.AuditEventSink;
import co.edu.retencion.audit.domain.ChainVerification;
import co.edu.retencion.audit.domain.HashChain;
import co.edu.retencion.audit.infrastructure.MeteredAuditSinkDecorator;
import co.edu.retencion.audit.infrastructure.PiiScrubbingAuditSinkDecorator;

/** Builder, hash encadenado y decoradores de la auditoría. */
class AuditDomainTest {

    private static AuditEntry entry(String entityId) {
        return AuditEntry.builder()
                .id(UUID.randomUUID())
                .occurredAt(Instant.parse("2026-11-03T15:00:00.123456789Z"))
                .actor(UUID.randomUUID(), "ADMIN")
                .action("TEST")
                .entity("Test", entityId)
                .outcome(AuditOutcome.SUCCESS)
                .ipAddress("10.0.0.1")
                .requestId("req-123456")
                .reason("motivo")
                .details(Map.of("b", "2", "a", "1"))
                .build();
    }

    @Test
    void builderValidatesAndNormalizes() {
        AuditEntry e = entry("x".repeat(150));
        assertThat(e.entityId()).hasSize(100);
        assertThat(e.occurredAt().getNano() % 1000).isZero(); // truncado a microsegundos
        assertThatThrownBy(() -> AuditEntry.builder().id(UUID.randomUUID()).occurredAt(Instant.now())
                .outcome(AuditOutcome.SUCCESS).build()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hashDependsOnEveryFieldAndOnPreviousHash() {
        AuditEntry e = entry("1");
        String h = HashChain.compute(HashChain.GENESIS, 1, e);
        assertThat(h).hasSize(64).isEqualTo(HashChain.compute(HashChain.GENESIS, 1, e));
        assertThat(HashChain.compute(HashChain.GENESIS, 2, e)).isNotEqualTo(h);
        assertThat(HashChain.compute("f".repeat(64), 1, e)).isNotEqualTo(h);
        assertThat(HashChain.compute(HashChain.GENESIS, 1, e.toBuilder().reason("otro").build())).isNotEqualTo(h);
        assertThat(HashChain.compute(HashChain.GENESIS, 1, e.toBuilder().details(Map.of("a", "1")).build())).isNotEqualTo(h);
    }

    @Test
    void decoratorsScrubPiiAndCountEntries() {
        AtomicReference<AuditEntry> stored = new AtomicReference<>();
        AuditEventSink base = e -> {
            stored.set(e);
            return e.chained(1, HashChain.GENESIS, HashChain.compute(HashChain.GENESIS, 1, e));
        };
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AuditEventSink sink = new MeteredAuditSinkDecorator(new PiiScrubbingAuditSinkDecorator(base), registry);

        sink.append(entry("1").toBuilder()
                .reason("revisión pedida por juan@correo.com")
                .details(Map.of("nombreCompleto", "Juan Pérez", "documento", "1.023.456.789",
                        "nota", "token eyJhbGciOi.eyJzdWIiOiIx.firma", "programa", "Medicina"))
                .build());

        AuditEntry clean = stored.get();
        assertThat(clean.reason()).doesNotContain("juan@correo.com");
        assertThat(clean.details()).containsEntry("nombreCompleto", "[REDACTADO]")
                .containsEntry("documento", "[REDACTADO]")
                .containsEntry("programa", "Medicina");
        assertThat(clean.details().get("nota")).doesNotContain("eyJ");
        assertThat(registry.counter("audit.entries", "outcome", "SUCCESS").count()).isEqualTo(1.0);
    }

    @Test
    void verificationResultFactories() {
        assertThat(ChainVerification.ok(5).valid()).isTrue();
        ChainVerification broken = ChainVerification.broken(3, 4, "alterada");
        assertThat(broken.valid()).isFalse();
        assertThat(broken.firstBrokenSeq()).isEqualTo(4);
    }
}
