package co.edu.retencion.audit.infrastructure;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import co.edu.retencion.audit.domain.AuditEntry;
import co.edu.retencion.audit.domain.AuditEventSink;

/**
 * <b>Patrón estructural: Decorator.</b> Añade métricas Micrometer (conteo por resultado y
 * latencia de escritura) a cualquier {@link AuditEventSink}, sin tocar su implementación.
 * Las etiquetas tienen cardinalidad baja (solo el resultado), nunca ids de usuario.
 */
public class MeteredAuditSinkDecorator implements AuditEventSink {

    private final AuditEventSink delegate;
    private final MeterRegistry registry;
    private final Timer timer;

    public MeteredAuditSinkDecorator(AuditEventSink delegate, MeterRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
        this.timer = Timer.builder("audit.append").description("Tiempo de escritura en la auditoría").register(registry);
    }

    @Override
    public AuditEntry append(AuditEntry entry) {
        AuditEntry stored = timer.record(() -> delegate.append(entry));
        Counter.builder("audit.entries")
                .tag("outcome", entry.outcome().name())
                .register(registry)
                .increment();
        return stored;
    }
}
