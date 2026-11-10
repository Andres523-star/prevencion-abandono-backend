package co.edu.retencion.records.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.academic.EffectiveAttendancePolicy;
import co.edu.retencion.academic.GroupInfo;
import co.edu.retencion.academic.SubjectInfo;
import co.edu.retencion.academic.TermInfo;
import co.edu.retencion.common.time.TimeConfig;
import co.edu.retencion.records.AttendanceLevel;
import co.edu.retencion.records.RecordsSnapshot;
import co.edu.retencion.records.RecordsSnapshot.OpenCause;
import co.edu.retencion.records.RecordsSnapshot.SubjectSnapshot;
import co.edu.retencion.records.StudentRecords;
import co.edu.retencion.records.domain.AbsenceCounter;
import co.edu.retencion.records.domain.AbsenceCounterRepository;
import co.edu.retencion.records.domain.DropoutCause;
import co.edu.retencion.records.domain.DropoutCauseRepository;
import co.edu.retencion.records.domain.Grade;
import co.edu.retencion.records.domain.GradeIndicators;
import co.edu.retencion.records.domain.GradeRepository;
import co.edu.retencion.records.domain.Observation;
import co.edu.retencion.records.domain.ObservationRepository;
import co.edu.retencion.records.domain.StudentCause;
import co.edu.retencion.records.domain.StudentCauseRepository;
import co.edu.retencion.students.EnrollmentInfo;
import co.edu.retencion.students.StudentRegistry;

/**
 * Implementación de {@link StudentRecords}: arma la foto académica de un estudiante (notas,
 * faltas, observaciones y causas) en un periodo. Solo métricas; el texto de las observaciones se
 * entrega aparte y únicamente cuando la configuración permite texto libre hacia la IA.
 */
@Service
public class StudentRecordsService implements StudentRecords, co.edu.retencion.records.CauseRegistry {

    /** Sin periodo de referencia, las observaciones se cuentan en esta ventana. */
    static final int DEFAULT_OBSERVATION_WINDOW_DAYS = 120;

    private final StudentRegistry students;
    private final AcademicDirectory academic;
    private final AbsenceCounterRepository counters;
    private final GradeRepository grades;
    private final ObservationRepository observations;
    private final StudentCauseRepository studentCauses;
    private final DropoutCauseRepository causes;
    private final Clock clock;
    private final co.edu.retencion.records.domain.AttendanceRecordRepository attendance;

    public StudentRecordsService(StudentRegistry students, AcademicDirectory academic, AbsenceCounterRepository counters,
                                 GradeRepository grades, ObservationRepository observations,
                                 StudentCauseRepository studentCauses, DropoutCauseRepository causes, Clock clock,
                                 co.edu.retencion.records.domain.AttendanceRecordRepository attendance) {
        this.attendance = attendance;
        this.students = students;
        this.academic = academic;
        this.counters = counters;
        this.grades = grades;
        this.observations = observations;
        this.studentCauses = studentCauses;
        this.causes = causes;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public RecordsSnapshot snapshot(UUID studentId, UUID termId) {
        List<EnrollmentInfo> enrollments = students.enrollmentsOf(studentId);
        Optional<TermInfo> term = termId != null ? academic.findTerm(termId) : currentTerm(enrollments);
        if (term.isEmpty()) {
            Instant now = clock.instant();
            return new RecordsSnapshot(studentId, null, List.of(), null, 0,
                    countObservations(studentId, Observation.Type.ACADEMICA,
                            now.minusSeconds(DEFAULT_OBSERVATION_WINDOW_DAYS * 86_400L), now),
                    countObservations(studentId, Observation.Type.CONDUCTA,
                            now.minusSeconds(DEFAULT_OBSERVATION_WINDOW_DAYS * 86_400L), now),
                    openCauses(studentId, null));
        }
        TermInfo t = term.get();
        boolean closed = "CLOSED".equals(t.status());
        List<EnrollmentInfo> ofTerm = enrollments.stream()
                .filter(e -> e.termId().equals(t.id()) && (e.active() || closed)).toList();

        Map<UUID, AbsenceCounter> counterByEnrollment = counters.findByStudentIdAndTermId(studentId, t.id()).stream()
                .collect(Collectors.toMap(AbsenceCounter::getEnrollmentId, Function.identity(), (a, b) -> a));
        List<Grade> termGrades = grades.findByStudentIdAndTermIdOrderByAssessedOnAsc(studentId, t.id());
        GradeIndicators.Summary indicators = GradeIndicators.compute(termGrades.stream()
                .map(g -> new GradeIndicators.Point(g.getGroupId(), g.getValue(), g.getAssessedOn())).toList());
        Map<UUID, GradeIndicators.SubjectIndicator> byGroup = new HashMap<>();
        indicators.subjects().forEach(s -> byGroup.put(s.groupId(), s));

        List<SubjectSnapshot> subjects = new ArrayList<>();
        for (EnrollmentInfo enrollment : ofTerm) {
            Optional<GroupInfo> group = academic.findGroup(enrollment.groupId());
            String subjectName = group.flatMap(g -> academic.findSubject(g.subjectId())).map(SubjectInfo::name)
                    .orElse(null);
            EffectiveAttendancePolicy policy = academic.effectivePolicy(enrollment.groupId());
            AbsenceCounter counter = counterByEnrollment.get(enrollment.id());
            GradeIndicators.SubjectIndicator grade = byGroup.get(enrollment.groupId());
            subjects.add(new SubjectSnapshot(enrollment.id(), enrollment.groupId(),
                    group.map(GroupInfo::subjectId).orElse(null), subjectName,
                    grade == null ? null : grade.average(), grade == null ? 0 : grade.gradeCount(),
                    grade != null && grade.belowPassing(),
                    grade == null ? GradeIndicators.Trend.INSUFICIENTE.name() : grade.trend().name(),
                    counter == null ? 0 : counter.getCountedAbsences(),
                    counter == null ? 0 : counter.getUnjustified(),
                    counter == null ? 0 : counter.getPending(),
                    counter == null ? 0 : counter.getTardies(),
                    counter == null ? policy.maxAbsences() : counter.getMaxAbsences(),
                    policy.warningThreshold(), policy.criticalThreshold(),
                    counter == null ? AttendanceLevel.NINGUNO : counter.getLevel()));
        }
        Instant from = t.startDate().atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        Instant to = t.endDate().plusDays(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        return new RecordsSnapshot(studentId, t.id(), subjects, indicators.semesterAverage(),
                indicators.subjectsBelowPassing(),
                countObservations(studentId, Observation.Type.ACADEMICA, from, to),
                countObservations(studentId, Observation.Type.CONDUCTA, from, to),
                openCauses(studentId, closed ? t : null));
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> recentObservationTexts(UUID studentId, int limit) {
        return observations.findByStudentIdOrderByCreatedAtDesc(studentId).stream()
                .limit(Math.max(0, Math.min(limit, 20))).map(Observation::getText).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<WeeklyAbsences> weeklyAbsences(UUID studentId, java.util.Set<UUID> groupIds) {
        Map<UUID, Map<LocalDate, Integer>> byGroup = new HashMap<>();
        for (var record : attendance.findByStudentIdOrderByRecordedAtDesc(studentId)) {
            if (!groupIds.contains(record.getGroupId())
                    || record.getStatus() != co.edu.retencion.records.AttendanceStatus.FALTA
                    || record.getJustification() == co.edu.retencion.records.JustificationState.APROBADA) {
                continue;
            }
            academic.findSession(record.getSessionId()).ifPresent(session -> {
                LocalDate monday = session.date().with(java.time.DayOfWeek.MONDAY);
                byGroup.computeIfAbsent(record.getGroupId(), k -> new java.util.TreeMap<>()).merge(monday, 1, Integer::sum);
            });
        }
        List<WeeklyAbsences> out = new ArrayList<>();
        byGroup.forEach((group, weeks) -> weeks.forEach((week, n) -> out.add(new WeeklyAbsences(group, week, n))));
        out.sort(Comparator.comparing(WeeklyAbsences::weekStart).thenComparing(w -> w.groupId().toString()));
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public List<GradePoint> grades(UUID studentId, java.util.Set<UUID> groupIds) {
        return grades.findByStudentIdOrderByAssessedOnAsc(studentId).stream()
                .filter(g -> groupIds.contains(g.getGroupId()))
                .map(g -> new GradePoint(g.getGroupId(), g.getValue(), g.getAssessedOn())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActiveCause(String code) {
        return code != null && causes.findById(code).filter(DropoutCause::isActive).isPresent();
    }

    @Override
    @Transactional
    public void recordExitCause(UUID studentId, String code, UUID actor) {
        if (studentCauses.findByStudentIdAndCauseCodeAndResolvedAtIsNull(studentId, code).isEmpty()) {
            studentCauses.save(StudentCause.open(studentId, code, StudentCause.Source.ENTREVISTA, null, actor,
                    clock.instant()));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<NearLimitRow> nearLimit(UUID termId, UUID after, int limit) {
        return counters.nearLimit(termId == null, termId, after == null, after,
                        org.springframework.data.domain.Limit.of(limit)).stream()
                .map(c -> new NearLimitRow(c.getEnrollmentId(), c.getStudentId(), c.getGroupId(), c.getCountedAbsences(),
                        c.getMaxAbsences(), c.getLevel())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<co.edu.retencion.records.AbsenceInfo> absences(UUID studentId) {
        return attendance.findByStudentIdOrderByRecordedAtDesc(studentId).stream()
                .filter(r -> r.getStatus() != co.edu.retencion.records.AttendanceStatus.PRESENTE)
                .map(r -> new co.edu.retencion.records.AbsenceInfo(r.getId(), r.getTermId(), r.getSessionId(),
                        r.getEnrollmentId(), r.getStudentId(), r.getGroupId(),
                        academic.findSession(r.getSessionId()).map(s -> s.date()).orElse(null), r.getStatus(),
                        r.getJustification(), r.getJustificationDeadline()))
                .sorted(java.util.Comparator.comparing(co.edu.retencion.records.AbsenceInfo::sessionDate,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .toList();
    }

    /** Periodo más reciente no cerrado con inscripciones activas; si no hay, el más reciente. */
    private Optional<TermInfo> currentTerm(List<EnrollmentInfo> enrollments) {
        List<TermInfo> terms = enrollments.stream().map(EnrollmentInfo::termId).distinct()
                .map(academic::findTerm).flatMap(Optional::stream)
                .sorted(Comparator.comparing(TermInfo::startDate).reversed()).toList();
        return terms.stream().filter(t -> !"CLOSED".equals(t.status()))
                .filter(t -> enrollments.stream().anyMatch(e -> e.termId().equals(t.id()) && e.active()))
                .findFirst().or(() -> terms.stream().findFirst());
    }

    private int countObservations(UUID studentId, Observation.Type type, Instant from, Instant to) {
        return (int) observations.findByStudentIdOrderByCreatedAtDesc(studentId).stream()
                .filter(o -> o.getType() == type)
                .filter(o -> !o.getCreatedAt().isBefore(from) && o.getCreatedAt().isBefore(to)).count();
    }

    /**
     * Causas abiertas hoy; para un periodo cerrado, las vigentes durante ese periodo (dataset de
     * entrenamiento sin fuga de información posterior).
     */
    private List<OpenCause> openCauses(UUID studentId, TermInfo closedTerm) {
        Map<String, String> categories = causes.findAll().stream()
                .collect(Collectors.toMap(DropoutCause::getCode, c -> c.getCategory().name()));
        return studentCauses.findByStudentIdOrderByRecordedAtDesc(studentId).stream()
                .filter(c -> closedTerm == null ? c.isOpen() : activeDuring(c, closedTerm))
                .map(c -> new OpenCause(c.getCauseCode(), categories.getOrDefault(c.getCauseCode(), "OTRA")))
                .toList();
    }

    private static boolean activeDuring(StudentCause cause, TermInfo term) {
        LocalDate recorded = LocalDate.ofInstant(cause.getRecordedAt(), TimeConfig.BUSINESS_ZONE);
        boolean startedBeforeEnd = !recorded.isAfter(term.endDate());
        boolean notResolvedBeforeStart = cause.getResolvedAt() == null
                || !LocalDate.ofInstant(cause.getResolvedAt(), TimeConfig.BUSINESS_ZONE).isBefore(term.startDate());
        return startedBeforeEnd && notResolvedBeforeStart;
    }
}
