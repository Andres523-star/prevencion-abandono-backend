package co.edu.retencion.mailing.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import co.edu.retencion.settings.SettingsProvider;

/**
 * Política de retención del correo: los avisos ya cerrados (enviados, cancelados, fallidos, rebotados o
 * suprimidos) se borran tras {@code mail.outbox-retention-days}. Los pendientes nunca se tocan.
 */
@Component
public class MailRetentionJob {

    private final JdbcTemplate jdbc;
    private final SettingsProvider settings;
    private final Clock clock;

    public MailRetentionJob(JdbcTemplate jdbc, SettingsProvider settings, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.mail.retention-cron:0 40 3 * * *}", zone = "America/Bogota")
    public int purge() {
        Duration keep = Duration.ofDays(Math.max(1, settings.getInt("mail.outbox-retention-days", 180)));
        return jdbc.update("DELETE FROM email_outbox WHERE status NOT IN ('PENDIENTE', 'ENVIANDO') AND created_at < ?",
                Timestamp.from(clock.instant().minus(keep)));
    }
}
