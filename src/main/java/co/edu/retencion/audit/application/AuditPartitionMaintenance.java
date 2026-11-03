package co.edu.retencion.audit.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Crea por adelantado las particiones mensuales de {@code audit_log} (función SQL
 * {@code audit_ensure_partitions}). Corre al arrancar y cada madrugada (hora de Bogotá).
 * Si no se ejecutara, las filas caen en la partición DEFAULT y no se pierden.
 */
@Component
public class AuditPartitionMaintenance {

    private static final Logger log = LoggerFactory.getLogger(AuditPartitionMaintenance.class);

    private final JdbcTemplate jdbc;
    private final int monthsAhead;

    public AuditPartitionMaintenance(JdbcTemplate jdbc, @Value("${app.audit.partition-months-ahead:3}") int monthsAhead) {
        this.jdbc = jdbc;
        this.monthsAhead = monthsAhead;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "0 15 3 * * *", zone = "America/Bogota")
    public void ensurePartitions() {
        try {
            jdbc.execute("SELECT audit_ensure_partitions(" + monthsAhead + ")");
        } catch (RuntimeException ex) {
            log.error("No se pudieron crear las particiones de auditoría", ex);
        }
    }
}
