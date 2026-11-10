package co.edu.retencion.ai.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import co.edu.retencion.settings.SettingsProvider;

/**
 * Política de retención del registro técnico de llamadas a la IA ({@code ai_calls}, sin contenido) tras
 * {@code ai.calls-retention-days}. El historial de análisis de riesgo no se borra aquí: es parte del
 * expediente de acompañamiento.
 */
@Component
public class AiCallRetentionJob {

    private final JdbcTemplate jdbc;
    private final SettingsProvider settings;
    private final Clock clock;

    public AiCallRetentionJob(JdbcTemplate jdbc, SettingsProvider settings, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.ai.retention-cron:0 50 3 * * *}", zone = "America/Bogota")
    public int purge() {
        Duration keep = Duration.ofDays(Math.max(1, settings.getInt("ai.calls-retention-days", 365)));
        return jdbc.update("DELETE FROM ai_calls WHERE created_at < ?", Timestamp.from(clock.instant().minus(keep)));
    }
}
