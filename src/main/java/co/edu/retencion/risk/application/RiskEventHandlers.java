package co.edu.retencion.risk.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import co.edu.retencion.justifications.JustificationDecided;
import co.edu.retencion.records.AbsenceRecorded;
import co.edu.retencion.records.AttendanceLevel;
import co.edu.retencion.records.AttendanceThresholdReached;
import co.edu.retencion.records.GradeRecorded;
import co.edu.retencion.records.SubjectLostByAbsences;
import co.edu.retencion.risk.RiskTrigger;
import co.edu.retencion.risk.domain.RiskStudentState;
import co.edu.retencion.risk.domain.RiskStudentStateRepository;

/**
 * Consume los eventos de notas y asistencia. Solo encola trabajo (y marca vigilancia): el análisis
 * y la IA corren en {@link RiskWorker}, fuera de la petición que originó el evento.
 *
 * <ul>
 *   <li>Nota, falta o justificación aprobada: reanálisis con antirrebote ({@code risk.event-debounce-seconds}).</li>
 *   <li>Umbral de asistencia o pérdida: vigilancia, reanálisis inmediato y plan de apoyo.</li>
 *   <li>Justificación rechazada: vigilancia (la falta queda injustificada) y reanálisis.</li>
 * </ul>
 */
@Component
public class RiskEventHandlers {

    private final RiskWorkQueue queue;
    private final RiskSettings settings;
    private final RiskStudentStateRepository states;
    private final Clock clock;

    public RiskEventHandlers(RiskWorkQueue queue, RiskSettings settings, RiskStudentStateRepository states,
                             Clock clock) {
        this.queue = queue;
        this.settings = settings;
        this.states = states;
        this.clock = clock;
    }

    @ApplicationModuleListener
    void on(GradeRecorded event) {
        debounced(event.studentId());
    }

    @ApplicationModuleListener
    void on(AbsenceRecorded event) {
        debounced(event.studentId());
    }

    @ApplicationModuleListener
    void on(JustificationDecided event) {
        if (!event.approved()) {
            watch(event.studentId(), "JUSTIFICACION_RECHAZADA");
        }
        debounced(event.studentId());
    }

    @ApplicationModuleListener
    void on(AttendanceThresholdReached event) {
        threshold(event.studentId(), event.enrollmentId(), event.groupId(), event.level());
    }

    @ApplicationModuleListener
    void on(SubjectLostByAbsences event) {
        threshold(event.studentId(), event.enrollmentId(), event.groupId(), AttendanceLevel.PERDIDA);
    }

    private void threshold(UUID studentId, UUID enrollmentId, UUID groupId, AttendanceLevel level) {
        Instant now = clock.instant();
        watch(studentId, "UMBRAL_" + level.name());
        queue.enqueueAnalysis(studentId, RiskTrigger.THRESHOLD, level, now, now);
        queue.enqueuePlan(studentId, enrollmentId, groupId, level, now);
    }

    private void debounced(UUID studentId) {
        Instant now = clock.instant();
        queue.enqueueAnalysis(studentId, RiskTrigger.EVENT, null, now.plus(settings.snapshot().debounce()), now);
    }

    private void watch(UUID studentId, String reason) {
        Instant now = clock.instant();
        RiskStudentState state = states.findForUpdate(studentId).orElseGet(() -> RiskStudentState.of(studentId, now));
        state.watch(now.plus(Duration.ofDays(settings.snapshot().watchDays())), reason, now);
        states.save(state);
    }
}
