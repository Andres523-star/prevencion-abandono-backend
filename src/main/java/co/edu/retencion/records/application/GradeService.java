package co.edu.retencion.records.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
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

import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.academic.GroupInfo;
import co.edu.retencion.academic.SubjectInfo;
import co.edu.retencion.common.data.UpsertOutcome;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.records.GradeBook;
import co.edu.retencion.records.GradeRecorded;
import co.edu.retencion.records.application.RecordsViews.GradeView;
import co.edu.retencion.records.application.RecordsViews.IndicatorsView;
import co.edu.retencion.records.application.RecordsViews.SubjectIndicatorView;
import co.edu.retencion.records.domain.Grade;
import co.edu.retencion.records.domain.GradeIndicators;
import co.edu.retencion.records.domain.GradeRepository;
import co.edu.retencion.students.EnrollmentInfo;
import co.edu.retencion.students.StudentRegistry;

/**
 * Notas: registro individual o por lote (idempotente por inscripción + fecha + descripción),
 * corrección, borrado lógico e indicadores de riesgo. No calcula la nota oficial.
 */
@Service
public class GradeService implements GradeBook {

    private final GradeRepository grades;
    private final StudentRegistry students;
    private final AcademicDirectory academic;
    private final RecordsAccess access;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public GradeService(GradeRepository grades, StudentRegistry students, AcademicDirectory academic,
                        RecordsAccess access, Clock clock, ApplicationEventPublisher events) {
        this.events = events;
        this.grades = grades;
        this.students = students;
        this.academic = academic;
        this.access = access;
        this.clock = clock;
    }

    /**
     * @param enrollmentId inscripción
     * @param value        nota 0.0–5.0
     * @param assessedOn   fecha de la evaluación
     * @param description  descripción opcional (p. ej. "Parcial 1")
     */
    public record Entry(UUID enrollmentId, BigDecimal value, LocalDate assessedOn, String description) {
    }

    @Transactional
    public List<GradeView> recordForGroup(UUID groupId, List<Entry> entries) {
        Map<UUID, EnrollmentInfo> enrolled = students.activeEnrollments(groupId).stream()
                .collect(Collectors.toMap(EnrollmentInfo::id, Function.identity()));
        UUID actor = CurrentUser.require().userId();
        return entries.stream().map(e -> {
            EnrollmentInfo enrollment = Optional.ofNullable(enrolled.get(e.enrollmentId()))
                    .orElseThrow(() -> DomainException.badRequest("La inscripción no pertenece al grupo"));
            return GradeView.of(upsertGrade(enrollment, e.value(), e.assessedOn(), e.description(), actor).grade());
        }).toList();
    }

    @Transactional
    public GradeView recordForEnrollment(UUID enrollmentId, Entry entry) {
        EnrollmentInfo enrollment = students.findEnrollment(enrollmentId)
                .filter(EnrollmentInfo::active)
                .orElseThrow(() -> DomainException.notFound("Inscripción activa no encontrada"));
        return GradeView.of(upsertGrade(enrollment, entry.value(), entry.assessedOn(), entry.description(),
                CurrentUser.require().userId()).grade());
    }

    @Transactional
    public GradeView correct(UUID gradeId, BigDecimal value, LocalDate assessedOn, String description) {
        Grade grade = load(gradeId);
        grade.correct(value, assessedOn, blankToNull(description), CurrentUser.require().userId(), clock.instant());
        publishChange(grade);
        return GradeView.of(grades.saveAndFlush(grade));
    }

    @Transactional
    public void delete(UUID gradeId) {
        Grade grade = load(gradeId);
        grade.delete(clock.instant());
        grades.saveAndFlush(grade);
        publishChange(grade);
    }

    @Transactional(readOnly = true)
    public List<GradeView> ofGroup(UUID groupId) {
        return grades.findByGroupIdOrderByAssessedOnAsc(groupId).stream().map(GradeView::of).toList();
    }

    @Transactional(readOnly = true)
    public List<GradeView> ofStudent(UUID studentId, UUID termId) {
        Optional<Set<UUID>> onlyGroups = access.teacherOnlyGroups(CurrentUser.require());
        List<Grade> rows = termId == null ? grades.findByStudentIdOrderByAssessedOnAsc(studentId)
                : grades.findByStudentIdAndTermIdOrderByAssessedOnAsc(studentId, termId);
        return rows.stream().filter(g -> onlyGroups.map(s -> s.contains(g.getGroupId())).orElse(true))
                .map(GradeView::of).toList();
    }

    /** Promedio por materia y semestre, materias bajo 3.0 y tendencia. */
    @Transactional(readOnly = true)
    public IndicatorsView indicators(UUID studentId, UUID termId) {
        Optional<Set<UUID>> onlyGroups = access.teacherOnlyGroups(CurrentUser.require());
        List<Grade> rows = (termId == null ? grades.findByStudentIdOrderByAssessedOnAsc(studentId)
                : grades.findByStudentIdAndTermIdOrderByAssessedOnAsc(studentId, termId)).stream()
                .filter(g -> onlyGroups.map(s -> s.contains(g.getGroupId())).orElse(true)).toList();
        GradeIndicators.Summary summary = GradeIndicators.compute(rows.stream()
                .map(g -> new GradeIndicators.Point(g.getGroupId(), g.getValue(), g.getAssessedOn())).toList());
        Map<UUID, String> names = new HashMap<>();
        List<SubjectIndicatorView> subjects = summary.subjects().stream().map(s -> new SubjectIndicatorView(
                s.groupId(), names.computeIfAbsent(s.groupId(), this::subjectName), s.average(), s.gradeCount(),
                s.belowPassing(), s.trend(), s.lastGrade())).toList();
        return new IndicatorsView(studentId, termId, summary.semesterAverage(), summary.subjectsBelowPassing(),
                Grade.PASSING, subjects);
    }

    @Override
    @Transactional
    public UpsertOutcome upsert(UUID enrollmentId, BigDecimal value, LocalDate assessedOn, String description,
                                UUID actor) {
        EnrollmentInfo enrollment = students.findEnrollment(enrollmentId)
                .orElseThrow(() -> DomainException.notFound("Inscripción no encontrada"));
        return upsertGrade(enrollment, value, assessedOn, description, actor).outcome();
    }

    private record Upserted(Grade grade, UpsertOutcome outcome) {
    }

    private Upserted upsertGrade(EnrollmentInfo enrollment, BigDecimal value, LocalDate assessedOn, String description,
                                 UUID actor) {
        if (assessedOn == null) {
            throw DomainException.badRequest("La fecha de la evaluación es obligatoria");
        }
        String normalizedDescription = blankToNull(description);
        Optional<Grade> existing = grades.findNatural(enrollment.id(), assessedOn, normalizedDescription);
        if (existing.isPresent()) {
            boolean changed = existing.get().correct(value, assessedOn, normalizedDescription, actor, clock.instant());
            if (changed) {
                publishChange(existing.get());
            }
            return new Upserted(existing.get(), changed ? UpsertOutcome.UPDATED : UpsertOutcome.UNCHANGED);
        }
        Grade grade = grades.save(Grade.record(enrollment.id(), enrollment.studentId(), enrollment.groupId(),
                enrollment.termId(), value, assessedOn, normalizedDescription, actor, clock.instant()));
        publishChange(grade);
        return new Upserted(grade, UpsertOutcome.CREATED);
    }

    /** El motor de riesgo reanaliza al estudiante (con antirrebote) cuando cambian sus notas. */
    private void publishChange(Grade grade) {
        events.publishEvent(new GradeRecorded(grade.getStudentId(), grade.getGroupId(), grade.getEnrollmentId()));
    }

    private String subjectName(UUID groupId) {
        return academic.findGroup(groupId).map(GroupInfo::subjectId).flatMap(academic::findSubject)
                .map(SubjectInfo::name).orElse(null);
    }

    private Grade load(UUID id) {
        return grades.findById(id).orElseThrow(() -> DomainException.notFound("Nota no encontrada"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
