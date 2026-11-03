package co.edu.retencion.academic.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.academic.GroupTeacherChanged;
import co.edu.retencion.academic.GroupType;
import co.edu.retencion.academic.application.AcademicViews.GenerationResult;
import co.edu.retencion.academic.application.AcademicViews.GroupView;
import co.edu.retencion.academic.application.AcademicViews.SessionView;
import co.edu.retencion.academic.domain.ClassSession;
import co.edu.retencion.academic.domain.ClassSessionRepository;
import co.edu.retencion.academic.domain.CourseGroup;
import co.edu.retencion.academic.domain.CourseGroupRepository;
import co.edu.retencion.academic.domain.Program;
import co.edu.retencion.academic.domain.Subject;
import co.edu.retencion.academic.domain.SubjectRepository;
import co.edu.retencion.academic.domain.Term;
import co.edu.retencion.academic.infrastructure.SessionJdbcWriter;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.AuthenticatedUser;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.common.time.TimeConfig;
import co.edu.retencion.common.web.CursorPage;
import co.edu.retencion.users.UserAccount;
import co.edu.retencion.users.UserDirectory;

/** Grupos, horarios y sesiones de clase. */
@Service
public class GroupService {

    private static final UUID NONE = new UUID(0, 0);

    private final CourseGroupRepository groups;
    private final SubjectRepository subjects;
    private final ClassSessionRepository sessions;
    private final ProgramService programService;
    private final CalendarService calendarService;
    private final SessionGeneration generation;
    private final SessionJdbcWriter sessionWriter;
    private final UserDirectory users;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public GroupService(CourseGroupRepository groups, SubjectRepository subjects, ClassSessionRepository sessions,
                        ProgramService programService, CalendarService calendarService, SessionGeneration generation,
                        SessionJdbcWriter sessionWriter, UserDirectory users, ApplicationEventPublisher events,
                        Clock clock) {
        this.groups = groups;
        this.subjects = subjects;
        this.sessions = sessions;
        this.programService = programService;
        this.calendarService = calendarService;
        this.generation = generation;
        this.sessionWriter = sessionWriter;
        this.users = users;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public GroupView create(UUID subjectId, UUID termId, String code, UUID teacherUserId, int capacity,
                            GroupType type, List<CourseGroup.Slot> schedule) {
        Subject subject = loadSubject(subjectId);
        Program program = programService.load(subject.getProgramId());
        Term term = calendarService.loadTerm(termId);
        if (term.getStatus() == Term.Status.CLOSED) {
            throw DomainException.conflict("No se pueden crear grupos en un periodo cerrado");
        }
        if (groups.findBySubjectIdAndTermIdAndCodeIgnoreCase(subjectId, termId, code).isPresent()) {
            throw DomainException.conflict("Ya existe el grupo " + code + " para esa materia y periodo");
        }
        requireTeacher(teacherUserId);
        CourseGroup group = CourseGroup.create(subject, program, termId, code, teacherUserId, capacity, type,
                clock.instant());
        group.replaceSchedule(schedule, clock.instant());
        return GroupView.of(groups.save(group), program.getId());
    }

    @Transactional(readOnly = true)
    public GroupView get(UUID id) {
        CourseGroup group = load(id);
        return GroupView.of(group, loadSubject(group.getSubjectId()).getProgramId());
    }

    /**
     * Listado con el mínimo necesario por rol: el docente solo ve sus grupos y el coordinador solo
     * los de sus programas.
     */
    @Transactional(readOnly = true)
    public CursorPage<GroupView> search(UUID termId, UUID subjectId, UUID after, int size) {
        AuthenticatedUser user = CurrentUser.require();
        boolean seesAll = user.hasRole(Role.ADMIN) || user.hasRole(Role.CONSEJERO) || user.hasRole(Role.PSICOLOGO);
        boolean coordinator = !seesAll && user.hasRole(Role.COORDINADOR);
        boolean teacherOnly = !seesAll && !coordinator;
        Set<UUID> programs = coordinator ? nonEmpty(user.programIds()) : Set.of(NONE);
        List<CourseGroup> rows = groups.search(termId == null, orNone(termId), subjectId == null, orNone(subjectId),
                !teacherOnly, teacherOnly ? user.userId() : NONE, !coordinator, programs,
                after == null, orNone(after), Limit.of(size + 1));
        Map<UUID, UUID> programBySubject = subjects.findAllById(rows.stream().map(CourseGroup::getSubjectId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Subject::getId, Subject::getProgramId));
        List<GroupView> views = rows.stream().map(g -> GroupView.of(g, programBySubject.get(g.getSubjectId()))).toList();
        return CursorPage.of(views, size, GroupView::id);
    }

    @Transactional
    public GroupView assignTeacher(UUID groupId, UUID teacherUserId) {
        requireTeacher(teacherUserId);
        CourseGroup group = load(groupId);
        UUID previous = group.assignTeacher(teacherUserId, clock.instant());
        groups.saveAndFlush(group);
        if (!teacherUserId.equals(previous)) {
            events.publishEvent(new GroupTeacherChanged(groupId, previous, teacherUserId));
        }
        return get(groupId);
    }

    /**
     * Reemplaza el horario. Las sesiones futuras aún programadas se regeneran con el nuevo horario
     * (si el periodo no está cerrado); las pasadas o ya dictadas no se tocan.
     */
    @Transactional
    public GroupView replaceSchedule(UUID groupId, List<CourseGroup.Slot> schedule) {
        CourseGroup group = load(groupId);
        if (group.replaceSchedule(schedule, clock.instant())) {
            groups.saveAndFlush(group);
            LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
            sessionWriter.deleteFutureScheduled(groupId, today);
            Term term = calendarService.loadTerm(group.getTermId());
            if (term.getStatus() != Term.Status.CLOSED) {
                UUID programId = loadSubject(group.getSubjectId()).getProgramId();
                generation.generate(group, term, programId, generation.calendarFor(term), today);
            }
        }
        return get(groupId);
    }

    @Transactional
    public GenerationResult generateSessions(UUID groupId) {
        CourseGroup group = load(groupId);
        Term term = calendarService.loadTerm(group.getTermId());
        UUID programId = loadSubject(group.getSubjectId()).getProgramId();
        return new GenerationResult(generation.generate(group, term, programId, generation.calendarFor(term)));
    }

    @Transactional
    public GenerationResult generateSessionsForTerm(UUID termId) {
        Term term = calendarService.loadTerm(termId);
        SessionGeneration.NonTeachingCalendar calendar = generation.calendarFor(term);
        List<CourseGroup> termGroups = groups.findByTermId(termId);
        Map<UUID, UUID> programBySubject = subjects.findAllById(termGroups.stream().map(CourseGroup::getSubjectId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Subject::getId, Subject::getProgramId));
        int created = 0;
        for (CourseGroup group : termGroups) {
            created += generation.generate(group, term, programBySubject.get(group.getSubjectId()), calendar);
        }
        return new GenerationResult(created);
    }

    @Transactional(readOnly = true)
    public List<SessionView> sessions(UUID groupId, LocalDate from, LocalDate to) {
        load(groupId);
        return sessions.findByGroupIdAndDateBetweenOrderByDateAscStartTimeAsc(groupId, from, to).stream()
                .map(SessionView::of).toList();
    }

    @Transactional
    public SessionView changeSessionStatus(UUID sessionId, ClassSession.Status status, String reason) {
        ClassSession session = sessions.findById(sessionId)
                .orElseThrow(() -> DomainException.notFound("Sesión no encontrada"));
        session.changeStatus(status, reason, clock.instant());
        return SessionView.of(sessions.saveAndFlush(session));
    }

    CourseGroup load(UUID id) {
        return groups.findById(id).orElseThrow(() -> DomainException.notFound("Grupo no encontrado"));
    }

    private Subject loadSubject(UUID id) {
        return subjects.findById(id).orElseThrow(() -> DomainException.notFound("Materia no encontrada"));
    }

    /** El docente debe existir, estar activo y tener el rol DOCENTE asignado en la plataforma. */
    void requireTeacher(UUID teacherUserId) {
        if (teacherUserId == null) {
            return;
        }
        boolean valid = users.findById(teacherUserId).filter(UserAccount::isActive)
                .map(u -> u.roles().contains(Role.DOCENTE)).orElse(false);
        if (!valid) {
            throw DomainException.badRequest("El docente indicado no existe o no tiene el rol DOCENTE");
        }
    }

    private static UUID orNone(UUID value) {
        return value == null ? NONE : value;
    }

    private static Set<UUID> nonEmpty(Set<UUID> ids) {
        return ids.isEmpty() ? Set.of(NONE) : ids;
    }
}
