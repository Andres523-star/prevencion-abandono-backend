package co.edu.retencion.academic.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.academic.AcademicCatalog;
import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.academic.EffectiveAttendancePolicy;
import co.edu.retencion.academic.GroupInfo;
import co.edu.retencion.academic.ProgramInfo;
import co.edu.retencion.academic.SessionInfo;
import co.edu.retencion.academic.SubjectInfo;
import co.edu.retencion.academic.TermInfo;
import co.edu.retencion.academic.domain.CourseGroup;
import co.edu.retencion.academic.domain.CourseGroupRepository;
import co.edu.retencion.academic.domain.Program;
import co.edu.retencion.academic.domain.ProgramRepository;
import co.edu.retencion.academic.domain.Subject;
import co.edu.retencion.academic.domain.SubjectRepository;
import co.edu.retencion.academic.domain.Term;
import co.edu.retencion.academic.domain.TermRepository;
import co.edu.retencion.common.data.UpsertOutcome;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.reference.ProgramReferences;
import co.edu.retencion.common.security.authorization.TeachingAssignmentProvider;

/**
 * Implementación de la API pública del módulo ({@link AcademicDirectory}, {@link AcademicCatalog})
 * y de las SPI de common para el ABAC del docente y la validación de programas.
 */
@Service
public class AcademicDirectoryService implements AcademicDirectory, AcademicCatalog, TeachingAssignmentProvider,
        ProgramReferences {

    private final ProgramRepository programs;
    private final TermRepository terms;
    private final SubjectRepository subjects;
    private final CourseGroupRepository groups;
    private final SubjectService subjectService;
    private final GroupService groupService;
    private final AttendancePolicyService policyService;
    private final CalendarService calendarService;
    private final co.edu.retencion.academic.domain.ClassSessionRepository sessions;
    private final co.edu.retencion.academic.domain.HolidayRepository holidayRepository;
    private final Clock clock;

    public AcademicDirectoryService(ProgramRepository programs, TermRepository terms, SubjectRepository subjects,
                                    CourseGroupRepository groups, SubjectService subjectService,
                                    GroupService groupService, AttendancePolicyService policyService,
                                    CalendarService calendarService,
                                    co.edu.retencion.academic.domain.ClassSessionRepository sessions,
                                    co.edu.retencion.academic.domain.HolidayRepository holidayRepository, Clock clock) {
        this.programs = programs;
        this.terms = terms;
        this.subjects = subjects;
        this.groups = groups;
        this.subjectService = subjectService;
        this.groupService = groupService;
        this.policyService = policyService;
        this.calendarService = calendarService;
        this.sessions = sessions;
        this.holidayRepository = holidayRepository;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ AcademicDirectory

    @Override
    @Transactional(readOnly = true)
    public Optional<ProgramInfo> findProgram(UUID programId) {
        return programs.findById(programId).map(AcademicDirectoryService::info);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProgramInfo> findProgramByCode(String code) {
        return programs.findByCodeIgnoreCase(code.trim()).map(AcademicDirectoryService::info);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TermInfo> findTermByCode(String code) {
        return terms.findByCode(code.trim()).map(AcademicDirectoryService::info);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<ProgramInfo> listPrograms() {
        return programs.findAll().stream().map(AcademicDirectoryService::info)
                .sorted(java.util.Comparator.comparing(ProgramInfo::code)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TermInfo> findTerm(UUID termId) {
        return terms.findById(termId).map(AcademicDirectoryService::info);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<TermInfo> listTerms() {
        java.util.List<TermInfo> all = new java.util.ArrayList<>(terms.findAllByOrderByStartDateDesc().stream()
                .map(AcademicDirectoryService::info).toList());
        java.util.Collections.reverse(all);
        return all;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findSubjectId(UUID programId, String subjectCode) {
        return subjects.findByProgramIdAndCodeIgnoreCase(programId, subjectCode.trim()).map(Subject::getId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GroupInfo> findGroup(UUID groupId) {
        return groups.findById(groupId).map(this::info);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GroupInfo> findGroup(UUID subjectId, UUID termId, String groupCode) {
        return groups.findBySubjectIdAndTermIdAndCodeIgnoreCase(subjectId, termId, groupCode.trim()).map(this::info);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> groupsTaughtBy(UUID teacherUserId) {
        return groups.findOpenGroupIdsByTeacher(teacherUserId);
    }

    @Override
    public EffectiveAttendancePolicy effectivePolicy(UUID groupId) {
        return policyService.effectiveForGroup(groupId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SessionInfo> findSession(UUID sessionId) {
        return sessions.findById(sessionId).flatMap(s -> groups.findById(s.getGroupId())
                .map(g -> new SessionInfo(s.getId(), g.getId(), g.getTermId(), s.getDate(), s.getStartTime(),
                        s.getEndTime(), s.getStatus().name())));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SubjectInfo> findSubject(UUID subjectId) {
        return subjects.findById(subjectId).map(s -> new SubjectInfo(s.getId(), s.getProgramId(), s.getCode(), s.getName()));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<LocalDate> holidays(LocalDate from, LocalDate to) {
        return holidayRepository.findByDateBetweenOrderByDateAsc(from, to).stream()
                .map(co.edu.retencion.academic.domain.Holiday::getDate).collect(java.util.stream.Collectors.toSet());
    }

    @Override
    @Transactional
    public void markSessionDelivered(UUID sessionId) {
        sessions.findById(sessionId)
                .filter(s -> s.getStatus() == co.edu.retencion.academic.domain.ClassSession.Status.PROGRAMADA)
                .ifPresent(s -> s.changeStatus(co.edu.retencion.academic.domain.ClassSession.Status.DICTADA, null,
                        clock.instant()));
    }

    // ------------------------------------------------------------------ ProgramReferences

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> missing(Collection<UUID> programIds) {
        if (programIds.isEmpty()) {
            return Set.of();
        }
        Set<UUID> missing = new HashSet<>(programIds);
        programs.findExistingIds(programIds).forEach(missing::remove);
        return missing;
    }

    // ------------------------------------------------------------------ AcademicCatalog

    @Override
    @Transactional
    public TermInfo ensureTerm(String code, String name, LocalDate startDate, LocalDate endDate, String status) {
        return info(terms.findByCode(code).orElseGet(() -> terms.save(
                Term.create(code, name, startDate, endDate, Term.Status.valueOf(status), clock.instant()))));
    }

    @Override
    @Transactional
    public Upsert upsertSubject(SubjectCommand c) {
        Set<UUID> prerequisites = new HashSet<>();
        for (String code : c.prerequisiteCodes()) {
            prerequisites.add(subjects.findByProgramIdAndCodeIgnoreCase(c.programId(), code)
                    .orElseThrow(() -> DomainException.badRequest("Prerrequisito inexistente: " + code)).getId());
        }
        Optional<Subject> existing = subjects.findByProgramIdAndCodeIgnoreCase(c.programId(), c.code());
        if (existing.isEmpty()) {
            Subject created = subjects.save(Subject.create(c.programId(), c.code(), c.name(), c.credits(),
                    c.suggestedSemester(), clock.instant()));
            subjectService.setPrerequisites(created, prerequisites);
            return new Upsert(created.getId(), UpsertOutcome.CREATED);
        }
        Subject subject = existing.get();
        boolean changed = subject.update(c.name(), c.credits(), c.suggestedSemester(), true, clock.instant());
        changed |= subjectService.setPrerequisites(subject, prerequisites);
        return new Upsert(subject.getId(), changed ? UpsertOutcome.UPDATED : UpsertOutcome.UNCHANGED);
    }

    @Override
    @Transactional
    public Upsert upsertGroup(GroupCommand c) {
        List<CourseGroup.Slot> slots = c.schedule().stream()
                .map(s -> new CourseGroup.Slot(s.day(), s.start(), s.end(), s.room())).toList();
        Optional<CourseGroup> existing = groups.findBySubjectIdAndTermIdAndCodeIgnoreCase(c.subjectId(), c.termId(), c.code());
        if (existing.isEmpty()) {
            UUID id = groupService.create(c.subjectId(), c.termId(), c.code(), c.teacherUserId(), c.capacity(),
                    c.groupType(), slots).id();
            return new Upsert(id, UpsertOutcome.CREATED);
        }
        CourseGroup group = existing.get();
        Subject subject = subjects.findById(group.getSubjectId()).orElseThrow();
        Program program = programs.findById(subject.getProgramId()).orElseThrow();
        boolean changed = group.update(program, c.capacity(), c.groupType(), clock.instant());
        if (!java.util.Objects.equals(group.getTeacherUserId(), c.teacherUserId())) {
            groupService.assignTeacher(group.getId(), c.teacherUserId());
            changed = true;
        }
        if (!slots.isEmpty()) {
            changed |= group.replaceSchedule(slots, clock.instant());
        }
        return new Upsert(group.getId(), changed ? UpsertOutcome.UPDATED : UpsertOutcome.UNCHANGED);
    }

    @Override
    @Transactional
    public int generateSessionsForTerm(UUID termId) {
        return groupService.generateSessionsForTerm(termId).created();
    }

    @Override
    public void loadColombianHolidays(int year) {
        calendarService.loadColombianHolidays(year);
    }

    // ------------------------------------------------------------------ mapeos

    private static ProgramInfo info(Program p) {
        return new ProgramInfo(p.getId(), p.getCode(), p.getName(), p.isClinical());
    }

    private static TermInfo info(Term t) {
        return new TermInfo(t.getId(), t.getCode(), t.getStartDate(), t.getEndDate(), t.getStatus().name());
    }

    private GroupInfo info(CourseGroup g) {
        UUID programId = subjects.findById(g.getSubjectId()).map(Subject::getProgramId).orElse(null);
        return new GroupInfo(g.getId(), g.getSubjectId(), programId, g.getTermId(), g.getCode(), g.getTeacherUserId(),
                g.getCapacity(), g.getGroupType());
    }
}
