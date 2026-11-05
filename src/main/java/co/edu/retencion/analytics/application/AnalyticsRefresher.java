package co.edu.retencion.analytics.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import co.edu.retencion.analytics.infrastructure.AnalyticsRepository;
import co.edu.retencion.justifications.JustificationDecided;
import co.edu.retencion.records.AbsenceRecorded;
import co.edu.retencion.records.AbsenceRetracted;
import co.edu.retencion.records.AttendanceThresholdReached;
import co.edu.retencion.records.SubjectLostByAbsences;
import co.edu.retencion.retention.RetentionCaseChanged;
import co.edu.retencion.risk.RiskAssessed;
import co.edu.retencion.settings.SettingsProvider;

/**
 * Mantiene al día el modelo de lectura. Los eventos de dominio solo marcan que hay cambios; un job los
 * agrupa y refresca como máximo cada {@code analytics.refresh-min-seconds}, porque refrescar por cada
 * falta registrada sería más caro que los datos que actualiza. Al refrescar sube la generación de la
 * caché. Una vez por noche se refresca siempre, por los cambios que no publican evento.
 */
@Service
public class AnalyticsRefresher {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsRefresher.class);

    private final AnalyticsRepository repository;
    private final AnalyticsCache cache;
    private final SettingsProvider settings;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final boolean enabled;

    public AnalyticsRefresher(AnalyticsRepository repository, AnalyticsCache cache, SettingsProvider settings,
                              PlatformTransactionManager transactions, Clock clock,
                              @Value("${app.analytics.refresh-enabled:true}") boolean enabled) {
        this.repository = repository;
        this.cache = cache;
        this.settings = settings;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
        this.enabled = enabled;
    }

    @ApplicationModuleListener
    void on(RiskAssessed event) {
        repository.markDirty(clock.instant());
    }

    @ApplicationModuleListener
    void on(AbsenceRecorded event) {
        repository.markDirty(clock.instant());
    }

    @ApplicationModuleListener
    void on(AbsenceRetracted event) {
        repository.markDirty(clock.instant());
    }

    @ApplicationModuleListener
    void on(AttendanceThresholdReached event) {
        repository.markDirty(clock.instant());
    }

    @ApplicationModuleListener
    void on(SubjectLostByAbsences event) {
        repository.markDirty(clock.instant());
    }

    @ApplicationModuleListener
    void on(JustificationDecided event) {
        repository.markDirty(clock.instant());
    }

    @ApplicationModuleListener
    void on(RetentionCaseChanged event) {
        repository.markDirty(clock.instant());
    }

    @Scheduled(fixedDelayString = "${app.analytics.refresh-check-ms:30000}",
            initialDelayString = "${app.analytics.refresh-check-ms:30000}")
    void refreshIfDirty() {
        if (!enabled) {
            return;
        }
        AnalyticsRepository.RefreshState state = repository.refreshState();
        Duration minimum = Duration.ofSeconds(settings.getInt("analytics.refresh-min-seconds", 300));
        Instant now = clock.instant();
        if (state.dirty() && (state.refreshedAt() == null || !state.refreshedAt().plus(minimum).isAfter(now))) {
            refresh();
        }
    }

    @Scheduled(cron = "${app.analytics.nightly-refresh-cron:0 30 2 * * *}", zone = "America/Bogota")
    void nightly() {
        if (enabled) {
            refresh();
        }
    }

    /** Refresca ahora; {@code false} si otra instancia ya lo estaba haciendo. */
    public boolean refresh() {
        long started = System.nanoTime();
        boolean done = Boolean.TRUE.equals(tx.execute(status -> repository.refresh(clock.instant())));
        if (done) {
            cache.invalidate();
            log.info("Vistas de analítica refrescadas en {} ms", (System.nanoTime() - started) / 1_000_000);
        }
        return done;
    }

    /** Invalida las respuestas sin refrescar (la serie histórica se lee directamente). */
    void invalidate() {
        cache.invalidate();
    }
}
