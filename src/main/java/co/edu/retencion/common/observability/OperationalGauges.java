package co.edu.retencion.common.observability;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Tamaño de las colas y bandejas que operación vigila en Grafana: eventos de dominio pendientes, correos,
 * cola del motor de riesgo, reportes y solicitudes del titular vencidas. Solo cuenta filas (sin datos
 * personales) y cachea cada valor unos segundos para que un scrape de Prometheus no golpee la base.
 *
 * <p>Es la única pieza que lee tablas de varios módulos: es un modelo de lectura operativo, de solo conteos.
 */
@Component
public class OperationalGauges implements MeterBinder {

    private static final Duration TTL = Duration.ofSeconds(15);

    private final JdbcTemplate jdbc;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public OperationalGauges(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Cached(long value, long at) {
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        gauge(registry, "retencion.events.pending", "Eventos de dominio sin completar (listener en curso o fallido)",
                "SELECT count(*) FROM event_publication WHERE completion_date IS NULL", Map.of());
        for (String status : new String[] {"PENDIENTE", "FALLIDO", "REBOTADO"}) {
            gauge(registry, "retencion.mail.outbox", "Avisos de correo por estado",
                    "SELECT count(*) FROM email_outbox WHERE status = '" + status + "'", Map.of("status", status));
        }
        gauge(registry, "retencion.risk.queue", "Trabajos pendientes del motor de riesgo",
                "SELECT count(*) FROM risk_work_queue", Map.of());
        gauge(registry, "retencion.reports.queue", "Reportes pendientes o en proceso",
                "SELECT count(*) FROM report_jobs WHERE status IN ('PENDIENTE', 'PROCESANDO')", Map.of());
        gauge(registry, "retencion.privacy.requests.overdue", "Solicitudes del titular vencidas sin respuesta",
                "SELECT count(*) FROM data_subject_requests WHERE status IN ('RADICADA', 'APROBADA') "
                        + "AND due_on < (now() AT TIME ZONE 'America/Bogota')::date", Map.of());
        gauge(registry, "retencion.alerts.open", "Alertas de riesgo abiertas",
                "SELECT count(*) FROM alerts WHERE status IN ('NUEVA', 'VISTA')", Map.of());
    }

    private void gauge(MeterRegistry registry, String name, String description, String sql, Map<String, String> tags) {
        String key = name + tags;
        Gauge.Builder<Supplier<Number>> builder = Gauge.builder(name, () -> value(key, sql)).description(description);
        tags.forEach(builder::tag);
        builder.register(registry);
    }

    private Number value(String key, String sql) {
        long now = System.nanoTime();
        Cached cached = cache.get(key);
        if (cached != null && now - cached.at() < TTL.toNanos()) {
            return cached.value();
        }
        try {
            Long count = jdbc.queryForObject(sql, Long.class);
            long value = count == null ? 0 : count;
            cache.put(key, new Cached(value, now));
            return value;
        } catch (RuntimeException ex) {
            // Base caída: la métrica no debe tumbar el scrape; la alerta de disponibilidad avisa.
            return cached == null ? Double.NaN : cached.value();
        }
    }
}
