package co.edu.retencion.records.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import co.edu.retencion.academic.EffectiveAttendancePolicy;
import co.edu.retencion.records.AttendanceLevel;
import co.edu.retencion.records.AttendanceThresholdReached;
import co.edu.retencion.records.CounterInfo;
import co.edu.retencion.records.SubjectLostByAbsences;
import co.edu.retencion.records.domain.AbsenceCounter;
import co.edu.retencion.records.domain.AbsenceCounterRepository;
import co.edu.retencion.records.domain.AbsenceTally;
import co.edu.retencion.records.domain.AttendanceRecordRepository;

/**
 * Recalcula el contador de una inscripción desde sus registros de asistencia (dentro de la
 * transacción del cambio) y emite los eventos cuando el nivel sube: AVISO o CRÍTICO →
 * {@link AttendanceThresholdReached}; máximo de faltas → {@link SubjectLostByAbsences}.
 */
@Component
class CounterCalculator {

    private final AttendanceRecordRepository records;
    private final AbsenceCounterRepository counters;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    CounterCalculator(AttendanceRecordRepository records, AbsenceCounterRepository counters,
                      ApplicationEventPublisher events, Clock clock) {
        this.records = records;
        this.counters = counters;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Crea en un solo paso los contadores que faltan de un grupo (en cero). Así la toma de lista no consulta
     * contador por contador para los estudiantes presentes, que son la gran mayoría.
     */
    void ensureCounters(java.util.Collection<co.edu.retencion.students.EnrollmentInfo> enrollments, UUID groupId,
                        UUID termId, EffectiveAttendancePolicy policy) {
        java.util.Set<UUID> existing = new java.util.HashSet<>();
        counters.findByGroupId(groupId).forEach(c -> existing.add(c.getEnrollmentId()));
        List<AbsenceCounter> missing = enrollments.stream().filter(e -> !existing.contains(e.id()))
                .map(e -> AbsenceCounter.empty(e.id(), e.studentId(), groupId, termId, policy.maxAbsences(), clock.instant()))
                .toList();
        if (!missing.isEmpty()) {
            counters.saveAll(missing);
        }
    }

    CounterInfo recalculate(UUID enrollmentId, UUID studentId, UUID groupId, UUID termId,
                            EffectiveAttendancePolicy policy) {
        AbsenceCounter counter = counters.findForUpdate(enrollmentId).orElseGet(() -> counters.save(
                AbsenceCounter.empty(enrollmentId, studentId, groupId, termId, policy.maxAbsences(), clock.instant())));
        List<AbsenceTally.Mark> marks = records.findByTermIdAndEnrollmentId(termId, enrollmentId).stream()
                .map(r -> new AbsenceTally.Mark(r.getStatus(), r.getJustification())).toList();
        AbsenceTally tally = AbsenceTally.of(marks, policy);
        AttendanceLevel previous = counter.apply(tally, policy.maxAbsences(), clock.instant());
        if (tally.level().isAbove(previous)) {
            if (tally.level() == AttendanceLevel.PERDIDA) {
                events.publishEvent(new SubjectLostByAbsences(enrollmentId, studentId, groupId,
                        tally.countedAbsences(), policy.maxAbsences()));
            } else {
                events.publishEvent(new AttendanceThresholdReached(enrollmentId, studentId, groupId, tally.level(),
                        tally.countedAbsences(), policy.maxAbsences()));
            }
        }
        return counter.info();
    }
}
