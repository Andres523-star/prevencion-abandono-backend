package co.edu.retencion.reports.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import co.edu.retencion.analytics.AnalyticsFilter;
import co.edu.retencion.analytics.AnalyticsViewer;
import co.edu.retencion.audit.AuditOutcome;
import co.edu.retencion.audit.AuditTrail;
import co.edu.retencion.common.files.ObjectStorage;
import co.edu.retencion.common.id.UuidV7;
import co.edu.retencion.common.time.TimeConfig;
import co.edu.retencion.reports.domain.ReportDocument;
import co.edu.retencion.reports.domain.ReportJob;
import co.edu.retencion.reports.domain.ReportJobRepository;
import co.edu.retencion.reports.domain.ReportTooLargeException;
import co.edu.retencion.reports.domain.ReportWriter;
import co.edu.retencion.reports.infrastructure.ReportWriterFactory;
import co.edu.retencion.settings.SettingsProvider;

/**
 * Genera los reportes en segundo plano. El archivo se escribe en un temporal fuera de toda transacción
 * (puede tardar minutos) y se borra al subirlo. Cualquier instancia puede tomar un trabajo; un trabajo
 * que quede sin dueño se reintenta hasta {@value #MAX_ATTEMPTS} veces.
 */
@Component
public class ReportWorker {

    private static final Logger log = LoggerFactory.getLogger(ReportWorker.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration LEASE = Duration.ofMinutes(15);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final ReportJobRepository jobs;
    private final ReportContentFactory content;
    private final ReportWriterFactory writers;
    private final ObjectStorage storage;
    private final AuditTrail audit;
    private final SettingsProvider settings;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final boolean enabled;

    public ReportWorker(ReportJobRepository jobs, ReportContentFactory content, ReportWriterFactory writers,
                        ObjectStorage storage, AuditTrail audit, SettingsProvider settings, ObjectMapper mapper,
                        PlatformTransactionManager transactions, Clock clock,
                        @Value("${app.reports.worker-enabled:true}") boolean enabled) {
        this.jobs = jobs;
        this.content = content;
        this.writers = writers;
        this.storage = storage;
        this.audit = audit;
        this.settings = settings;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.reports.poll-interval-ms:5000}", initialDelayString = "${app.reports.poll-interval-ms:5000}")
    void poll() {
        if (enabled) {
            drain();
        }
    }

    @Scheduled(cron = "${app.reports.expiry-cron:0 10 * * * *}", zone = "America/Bogota")
    void expiry() {
        if (enabled) {
            expireDue();
        }
    }

    /** Procesa la cola. @return reportes tomados */
    public int drain() {
        int processed = 0;
        Optional<UUID> next;
        while ((next = claim()).isPresent()) {
            process(next.get());
            processed++;
        }
        return processed;
    }

    private Optional<UUID> claim() {
        return tx.execute(status -> {
            Instant now = clock.instant();
            Optional<UUID> id = jobs.nextId(now);
            id.ifPresent(value -> jobs.lease(value, now.plus(LEASE)));
            return id;
        });
    }

    private void process(UUID id) {
        ReportJob job = jobs.findById(id).orElseThrow();
        if (job.getAttempts() > MAX_ATTEMPTS) {
            finish(id, j -> j.fail("too_many_attempts", clock.instant()));
            return;
        }
        Path file = null;
        try {
            file = Files.createTempFile("reporte-", "." + job.getFormat().extension());
            ReportDocument document = content.build(job, filter(job), viewer(job));
            ReportWriter writer = writers.forFormat(job.getFormat());
            long max = job.getFormat() == ReportJob.Format.PDF ? settings.getInt("reports.pdf-max-rows", 20_000) : Long.MAX_VALUE;
            long rows = writer.write(document, file, max);
            long size = Files.size(file);
            String key = "reportes/" + UuidV7.newId() + "-" + job.getFormat().extension();
            storage.put(key, file, job.getFormat().mediaType());
            Instant now = clock.instant();
            // Primero la auditoría: un reporte con identidad nunca queda disponible sin su registro.
            audit.recordAs(job.getRequestedBy(), "REPORT_GENERATED", "Report", id.toString(), AuditOutcome.SUCCESS,
                    Map.of("type", job.getType().name(), "format", job.getFormat().name(), "rows", String.valueOf(rows),
                            "includeIdentity", String.valueOf(job.isIncludeIdentity())));
            Duration life = Duration.ofHours(Math.max(1, settings.getInt("reports.expiry-hours", 24)));
            finish(id, j -> j.ready(key, fileName(j, now), size, rows, life, now));
        } catch (ReportTooLargeException ex) {
            finish(id, j -> j.fail("report_too_large", clock.instant()));
        } catch (IOException | RuntimeException ex) {
            log.error("No se pudo generar el reporte {}: {}", id, ex.getClass().getSimpleName());
            finish(id, j -> j.fail("generation_failed", clock.instant()));
        } finally {
            delete(file);
        }
    }

    private void finish(UUID id, java.util.function.Consumer<ReportJob> change) {
        tx.executeWithoutResult(status -> {
            ReportJob job = jobs.findById(id).orElseThrow();
            change.accept(job);
            jobs.save(job);
        });
    }

    /** Borra los archivos vencidos. @return reportes expirados */
    public int expireDue() {
        int expired = 0;
        for (ReportJob job : jobs.expired(clock.instant())) {
            storage.delete(job.getObjectKey());
            finish(job.getId(), ReportJob::expire);
            expired++;
        }
        return expired;
    }

    private AnalyticsFilter filter(ReportJob job) throws JsonProcessingException {
        return mapper.readValue(job.getFilters(), AnalyticsFilter.class);
    }

    private AnalyticsViewer viewer(ReportJob job) throws JsonProcessingException {
        if (job.getScopePrograms() == null) {
            return AnalyticsViewer.all();
        }
        return AnalyticsViewer.of(new HashSet<>(mapper.readValue(job.getScopePrograms(), new TypeReference<java.util.List<UUID>>() { })));
    }

    private String fileName(ReportJob job, Instant now) {
        return "reporte-" + job.getType().name().toLowerCase(Locale.ROOT).replace('_', '-') + "-"
                + STAMP.format(LocalDateTime.ofInstant(now, TimeConfig.BUSINESS_ZONE)) + "." + job.getFormat().extension();
    }

    private static void delete(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ex) {
                log.warn("No se pudo borrar el temporal de un reporte");
            }
        }
    }
}
