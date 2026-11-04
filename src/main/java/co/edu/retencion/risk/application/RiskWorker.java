package co.edu.retencion.risk.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import co.edu.retencion.risk.RiskTrigger;

/**
 * Procesa la cola del motor: reanálisis por eventos y planes de apoyo por umbrales. Corre cada
 * {@code app.risk.worker-interval-ms}; en pruebas se desactiva y se invoca {@link #drain(Instant)}.
 */
@Component
public class RiskWorker {

    private static final Logger log = LoggerFactory.getLogger(RiskWorker.class);

    private final RiskWorkQueue queue;
    private final RiskOrchestrator orchestrator;
    private final SupportPlanService plans;
    private final Clock clock;
    private final boolean enabled;

    public RiskWorker(RiskWorkQueue queue, RiskOrchestrator orchestrator, SupportPlanService plans, Clock clock,
                      @Value("${app.risk.worker-enabled:true}") boolean enabled) {
        this.queue = queue;
        this.orchestrator = orchestrator;
        this.plans = plans;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.risk.worker-interval-ms:15000}", initialDelayString = "${app.risk.worker-interval-ms:15000}")
    void scheduled() {
        if (enabled) {
            drain(clock.instant());
        }
    }

    /** Procesa todas las tareas vencidas a {@code now}; devuelve cuántas procesó. */
    public int drain(Instant now) {
        int processed = 0;
        List<RiskWorkQueue.Task> batch;
        while (!(batch = queue.claim(now, 20)).isEmpty()) {
            for (RiskWorkQueue.Task task : batch) {
                try {
                    if ("SUPPORT_PLAN".equals(task.kind())) {
                        plans.propose(task.studentId(), task.enrollmentId(), task.groupId(), task.attendanceLevel());
                    } else {
                        orchestrator.analyze(task.studentId(), task.trigger(), null,
                                task.trigger() == RiskTrigger.THRESHOLD ? task.attendanceLevel() : null);
                    }
                    processed++;
                } catch (RuntimeException ex) {
                    log.warn("Tarea de riesgo {} falló (intento {}): {}", task.kind(), task.attempts() + 1,
                            ex.getClass().getSimpleName());
                    if (!queue.retry(task, ex.getClass().getSimpleName(), now)) {
                        log.error("Tarea de riesgo {} descartada tras {} intentos", task.kind(), RiskWorkQueue.MAX_ATTEMPTS);
                    }
                }
            }
        }
        return processed;
    }
}
