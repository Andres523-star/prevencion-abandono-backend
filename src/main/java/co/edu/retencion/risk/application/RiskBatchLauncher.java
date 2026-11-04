package co.edu.retencion.risk.application;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Lanza el lote nocturno ({@code app.risk.batch.cron}, por defecto 01:30 hora de Bogotá). También se
 * puede lanzar a mano (ADMIN).
 */
@Component
public class RiskBatchLauncher {

    private static final Logger log = LoggerFactory.getLogger(RiskBatchLauncher.class);

    private final JobLauncher launcher;
    private final Job job;
    private final Clock clock;
    private final boolean enabled;

    public RiskBatchLauncher(JobLauncher launcher, Job nightlyRiskJob, Clock clock,
                             @Value("${app.risk.batch.enabled:true}") boolean enabled) {
        this.launcher = launcher;
        this.job = nightlyRiskJob;
        this.clock = clock;
        this.enabled = enabled;
    }

    /**
     * @param executionId ejecución del JobRepository
     * @param status      estado final
     * @param read        estudiantes leídos
     * @param analyzed    análisis guardados
     * @param unchanged   omitidos por no haber cambiado
     * @param skipped     omitidos por error
     */
    public record RunSummary(Long executionId, String status, long read, long analyzed, long unchanged, long skipped,
                             Instant startedAt) {
    }

    @Scheduled(cron = "${app.risk.batch.cron:0 30 1 * * *}", zone = "America/Bogota")
    void nightly() {
        if (enabled) {
            run();
        }
    }

    public RunSummary run() {
        return run(java.util.Set.of());
    }

    /** @param programCodes programas a procesar (vacío = todos) */
    public RunSummary run(java.util.Set<String> programCodes) {
        Instant started = clock.instant();
        try {
            JobExecution execution = launcher.run(job, new JobParametersBuilder()
                    .addLong("run.at", System.nanoTime())
                    .addString("programs", String.join(",", new java.util.TreeSet<>(programCodes)))
                    .toJobParameters());
            long read = 0;
            long written = 0;
            long filtered = 0;
            long skipped = 0;
            for (StepExecution step : execution.getStepExecutions()) {
                if (step.getStepName().startsWith("riskWorker:")) {
                    read += step.getReadCount();
                    written += step.getWriteCount();
                    filtered += step.getFilterCount();
                    skipped += step.getSkipCount();
                }
            }
            log.info("Lote de riesgo {}: {} leídos, {} analizados, {} sin cambios, {} con error",
                    execution.getStatus(), read, written, filtered, skipped);
            return new RunSummary(execution.getId(), execution.getStatus().name(), read, written, filtered, skipped,
                    started);
        } catch (Exception ex) {
            log.error("No se pudo ejecutar el lote de riesgo: {}", ex.getClass().getSimpleName());
            return new RunSummary(null, "FAILED", 0, 0, 0, 0, started);
        }
    }
}
