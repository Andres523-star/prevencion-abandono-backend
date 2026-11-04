package co.edu.retencion.records.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.academic.AcademicCatalog;
import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.academic.EffectiveAttendancePolicy;
import co.edu.retencion.academic.GroupInfo;
import co.edu.retencion.academic.SessionInfo;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.AuthenticatedUser;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.common.time.TimeConfig;
import co.edu.retencion.records.AbsenceRecorded;
import co.edu.retencion.records.AbsenceRetracted;
import co.edu.retencion.records.AttendanceStatus;
import co.edu.retencion.records.application.RecordsViews.AttendanceView;
import co.edu.retencion.records.application.RecordsViews.CounterView;
import co.edu.retencion.records.domain.AbsenceCounterRepository;
import co.edu.retencion.records.domain.AttendanceRecord;
import co.edu.retencion.records.domain.AttendanceRecordRepository;
import co.edu.retencion.records.domain.BusinessDays;
import co.edu.retencion.settings.SettingsProvider;
import co.edu.retencion.students.EnrollmentInfo;
import co.edu.retencion.students.StudentRegistry;

/**
 * Toma de asistencia por sesión (individual o por lote del grupo).
 *
 * <ul>
 *   <li>El docente edita dentro de la ventana (por defecto 48 h tras el fin de la sesión,
 *       configurable en {@code attendance.edit-window-hours}); después solo coordinación o
 *       administración corrigen, con motivo y auditoría.</li>
 *   <li>Cada cambio recalcula el contador en la misma transacción.</li>
 *   <li>Una nueva falta emite {@link AbsenceRecorded}; corregir una falta emite {@link AbsenceRetracted}.</li>
 * </ul>
 */
@Service
public class AttendanceService {

    static final String EDIT_WINDOW_KEY = "attendance.edit-window-hours";

    private final AttendanceRecordRepository records;
    private final AbsenceCounterRepository counters;
    private final CounterCalculator calculator;
    private final AttendancePartitions partitions;
    private final AcademicDirectory academic;
    private final AcademicCatalog catalog;
    private final StudentRegistry students;
    private final SettingsProvider settings;
    private final RecordsAccess access;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public AttendanceService(AttendanceRecordRepository records, AbsenceCounterRepository counters,
                             CounterCalculator calculator, AttendancePartitions partitions, AcademicDirectory academic,
                             AcademicCatalog catalog, StudentRegistry students, SettingsProvider settings,
                             RecordsAccess access, ApplicationEventPublisher events, Clock clock) {
        this.records = records;
        this.counters = counters;
        this.calculator = calculator;
        this.partitions = partitions;
        this.academic = academic;
        this.catalog = catalog;
        this.students = students;
        this.settings = settings;
        this.access = access;
        this.events = events;
        this.clock = clock;
    }

    /**
     * @param enrollmentId inscripción
     * @param status       asistencia
     */
    public record Entry(UUID enrollmentId, AttendanceStatus status) {
    }

    @Transactional
    public List<AttendanceView> record(UUID sessionId, List<Entry> entries, String correctionReason) {
        AuthenticatedUser user = CurrentUser.require();
        SessionInfo session = academic.findSession(sessionId)
                .orElseThrow(() -> DomainException.notFound("Sesión no encontrada"));
        if (session.isCancelled()) {
            throw DomainException.conflict("La sesión está cancelada");
        }
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        if (session.date().isAfter(today)) {
            throw DomainException.conflict("No se puede tomar asistencia de una sesión futura");
        }
        Instant editableUntil = editableUntil(session);
        if (clock.instant().isAfter(editableUntil)) {
            if (!access.isPrivileged(user)) {
                throw new DomainException(RecordsProblems.EDIT_WINDOW_CLOSED,
                        "La ventana de edición terminó; solicita la corrección a coordinación");
            }
            if (correctionReason == null || correctionReason.isBlank()) {
                throw new DomainException(RecordsProblems.CORRECTION_REASON_REQUIRED,
                        "Indica el motivo de la corrección en la cabecera X-Access-Reason");
            }
        }

        partitions.ensure(session.termId());
        GroupInfo group = academic.findGroup(session.groupId()).orElseThrow();
        EffectiveAttendancePolicy policy = academic.effectivePolicy(group.id());
        String subjectName = academic.findSubject(group.subjectId()).map(s -> s.name()).orElse("");
        LocalDate deadline = BusinessDays.add(session.date(), policy.justificationWindowBusinessDays(),
                academic.holidays(session.date(), session.date().plusDays(90)));
        Map<UUID, EnrollmentInfo> enrolled = students.activeEnrollments(group.id()).stream()
                .collect(Collectors.toMap(EnrollmentInfo::id, Function.identity()));

        List<AttendanceView> result = new ArrayList<>();
        Instant now = clock.instant();
        for (Entry entry : entries) {
            EnrollmentInfo enrollment = Optional.ofNullable(enrolled.get(entry.enrollmentId()))
                    .orElseThrow(() -> DomainException.badRequest("La inscripción no pertenece al grupo de la sesión"));
            AttendanceRecord record = records.findByTermIdAndSessionIdAndEnrollmentId(session.termId(), sessionId,
                            enrollment.id())
                    .orElseGet(() -> AttendanceRecord.create(session.termId(), sessionId, enrollment.id(),
                            enrollment.studentId(), group.id(), now));
            AttendanceStatus previous = record.getStatus();
            boolean changed = record.mark(entry.status(), deadline, user.userId(), now);
            records.save(record);
            if (!changed) {
                result.add(AttendanceView.of(record));
                continue;
            }
            calculator.recalculate(enrollment.id(), enrollment.studentId(), group.id(), session.termId(), policy);
            if (entry.status() == AttendanceStatus.FALTA) {
                events.publishEvent(new AbsenceRecorded(record.getId(), enrollment.id(), enrollment.studentId(),
                        group.id(), subjectName, session.date(), deadline, editableUntil));
            } else if (previous == AttendanceStatus.FALTA) {
                events.publishEvent(new AbsenceRetracted(record.getId()));
            }
            result.add(AttendanceView.of(record));
        }
        catalog.markSessionDelivered(sessionId);
        return result;
    }

    @Transactional(readOnly = true)
    public List<AttendanceView> ofSession(UUID sessionId) {
        SessionInfo session = academic.findSession(sessionId)
                .orElseThrow(() -> DomainException.notFound("Sesión no encontrada"));
        return records.findByTermIdAndSessionId(session.termId(), sessionId).stream().map(AttendanceView::of).toList();
    }

    @Transactional(readOnly = true)
    public List<AttendanceView> ofStudent(UUID studentId) {
        Optional<Set<UUID>> onlyGroups = access.teacherOnlyGroups(CurrentUser.require());
        return records.findByStudentIdOrderByRecordedAtDesc(studentId).stream()
                .filter(r -> onlyGroups.map(g -> g.contains(r.getGroupId())).orElse(true))
                .map(AttendanceView::of).toList();
    }

    @Transactional(readOnly = true)
    public List<CounterView> countersOfStudent(UUID studentId) {
        Optional<Set<UUID>> onlyGroups = access.teacherOnlyGroups(CurrentUser.require());
        return counters.findByStudentId(studentId).stream()
                .filter(c -> onlyGroups.map(g -> g.contains(c.getGroupId())).orElse(true))
                .map(CounterView::of).toList();
    }

    @Transactional(readOnly = true)
    public List<CounterView> countersOfGroup(UUID groupId) {
        return counters.findByGroupId(groupId).stream().map(CounterView::of).toList();
    }

    /** Recalcula los contadores del grupo desde cero (p. ej. después de cambiar la política). */
    @Transactional
    public List<CounterView> recalculateGroup(UUID groupId) {
        GroupInfo group = academic.findGroup(groupId).orElseThrow(() -> DomainException.notFound("Grupo no encontrado"));
        EffectiveAttendancePolicy policy = academic.effectivePolicy(groupId);
        for (EnrollmentInfo enrollment : students.activeEnrollments(groupId)) {
            calculator.recalculate(enrollment.id(), enrollment.studentId(), groupId, group.termId(), policy);
        }
        return countersOfGroup(groupId);
    }

    /** Fin de la ventana de edición: fin de la sesión (hora de Bogotá) + horas configuradas. */
    Instant editableUntil(SessionInfo session) {
        long hours = settings.getLong(EDIT_WINDOW_KEY, 48);
        return ZonedDateTime.of(session.date(), session.end(), TimeConfig.BUSINESS_ZONE).toInstant()
                .plus(Duration.ofHours(hours));
    }
}
