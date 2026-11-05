package co.edu.retencion.retention.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.AuthenticatedUser;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.common.security.authorization.AccessAction;
import co.edu.retencion.common.security.authorization.ScopeAuthorizationService;
import co.edu.retencion.common.time.TimeConfig;
import co.edu.retencion.records.CauseRegistry;
import co.edu.retencion.records.StudentRecords;
import co.edu.retencion.retention.RetentionFollowUpDue;
import co.edu.retencion.retention.domain.CaseTask;
import co.edu.retencion.retention.domain.CaseTaskRepository;
import co.edu.retencion.retention.domain.ContactAttempt;
import co.edu.retencion.retention.domain.ContactAttemptRepository;
import co.edu.retencion.retention.domain.FollowUp;
import co.edu.retencion.retention.domain.FollowUpRepository;
import co.edu.retencion.retention.domain.InterviewNote;
import co.edu.retencion.retention.domain.InterviewNoteRepository;
import co.edu.retencion.retention.domain.RetentionCase;
import co.edu.retencion.retention.domain.RetentionCaseRepository;
import co.edu.retencion.risk.RiskAssessed;
import co.edu.retencion.settings.SettingsProvider;
import co.edu.retencion.students.StudentInfo;
import co.edu.retencion.students.StudentRegistry;
import co.edu.retencion.students.StudentStatus;
import co.edu.retencion.users.UserDirectory;

/**
 * Casos de retención: apertura (automática, autorreporte, consejero), etapas, contactos, notas de
 * entrevista restringidas, plan con tareas, seguimiento a 7/15/30 días, cierre (con causa, encuesta y
 * cambio de estado del estudiante) y embudo.
 */
@Service
public class RetentionService {

    static final int[] FOLLOW_UP_DAYS = {7, 15, 30};

    private final RetentionCaseRepository cases;
    private final ContactAttemptRepository contacts;
    private final InterviewNoteRepository interviews;
    private final CaseTaskRepository tasks;
    private final FollowUpRepository followUps;
    private final StudentRegistry students;
    private final StudentRecords records;
    private final CauseRegistry causes;
    private final UserDirectory users;
    private final ScopeAuthorizationService scope;
    private final SettingsProvider settings;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final JdbcTemplate jdbc;

    public RetentionService(RetentionCaseRepository cases, ContactAttemptRepository contacts,
                            InterviewNoteRepository interviews, CaseTaskRepository tasks, FollowUpRepository followUps,
                            StudentRegistry students, StudentRecords records, CauseRegistry causes, UserDirectory users,
                            ScopeAuthorizationService scope, SettingsProvider settings, ApplicationEventPublisher events,
                            ObjectMapper mapper, Clock clock, JdbcTemplate jdbc) {
        this.cases = cases;
        this.contacts = contacts;
        this.interviews = interviews;
        this.tasks = tasks;
        this.followUps = followUps;
        this.students = students;
        this.records = records;
        this.causes = causes;
        this.users = users;
        this.scope = scope;
        this.settings = settings;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
        this.jdbc = jdbc;
    }

    public record CaseView(UUID id, UUID studentId, UUID programId, UUID termId, RetentionCase.Stage stage,
                           RetentionCase.Origin origin, UUID assignedCounselorId, RetentionCase.Outcome outcome,
                           String exitCauseCode, Instant openedAt, Instant contactedAt, Instant closedAt,
                           List<ContactView> contacts, List<TaskView> tasks, List<FollowUpView> followUps) {
    }

    public record ContactView(UUID id, ContactAttempt.Channel channel, ContactAttempt.Result result, String notes,
                              UUID attemptedBy, Instant attemptedAt) {
        @Override
        public String toString() {
            return "ContactView[" + id + "]";
        }
    }

    public record TaskView(UUID id, String title, LocalDate dueDate, Instant doneAt) {
    }

    public record FollowUpView(UUID id, FollowUp.Kind kind, int offsetDays, LocalDate dueOn, FollowUp.Status status,
                               Instant remindedAt) {
    }

    public record NoteView(UUID id, UUID authorId, String text, Instant createdAt) {
        @Override
        public String toString() {
            return "NoteView[" + id + "]";
        }
    }

    public record FunnelView(UUID programId, UUID termId, long detected, long contacted, long inProgress,
                             long retained, long postponed, long transferred, long withdrawn) {
    }

    // ------------------------------------------------------------------ apertura

    /** Detección automática: riesgo en el nivel configurado y sin caso abierto. */
    @ApplicationModuleListener
    void on(RiskAssessed event) {
        String minLevel = settings.getString("retention.auto-open-level", "ALTO");
        if (event.level().ordinal() < co.edu.retencion.risk.RiskLevel.valueOf(minLevel).ordinal()) {
            return;
        }
        lockStudent(event.studentId());
        if (cases.findOpenByStudent(event.studentId()).isPresent()) {
            return;
        }
        students.findById(event.studentId()).filter(s -> s.status() == StudentStatus.ACTIVO)
                .ifPresent(s -> openInternal(s, RetentionCase.Origin.AUTOMATICO, null, null));
    }

    @Transactional
    public CaseView openByCounselor(UUID studentId) {
        AuthenticatedUser user = CurrentUser.require();
        scope.check("STUDENT", studentId, AccessAction.WRITE);
        StudentInfo student = students.findById(studentId).orElseThrow(() -> DomainException.notFound("Estudiante no encontrado"));
        lockStudent(studentId);
        if (cases.findOpenByStudent(studentId).isPresent()) {
            throw DomainException.conflict("El estudiante ya tiene un caso de retención abierto");
        }
        UUID counselor = user.hasRole(Role.CONSEJERO) ? user.userId() : null;
        return view(openInternal(student, RetentionCase.Origin.CONSEJERO, counselor, user.userId()), user);
    }

    /** "Necesito ayuda / estoy pensando en retirarme" desde el portal del estudiante. */
    @Transactional
    public CaseView selfReport(String message) {
        AuthenticatedUser user = CurrentUser.require();
        UUID studentId = students.findStudentIdByUser(user.userId())
                .orElseThrow(() -> new AccessDeniedException("Tu usuario no está vinculado a un estudiante"));
        StudentInfo student = students.findById(studentId).orElseThrow();
        lockStudent(studentId);
        RetentionCase c = cases.findOpenByStudent(studentId)
                .orElseGet(() -> openInternal(student, RetentionCase.Origin.AUTORREPORTE, null, user.userId()));
        if (message != null && !message.isBlank()) {
            // El mensaje del estudiante queda como nota restringida de entrevista (cifrada).
            interviews.save(InterviewNote.write(c.getId(), user.userId(), "Autorreporte: " + message.strip(),
                    clock.instant()));
        }
        // El estudiante solo ve la confirmación, no las notas del personal.
        return new CaseView(c.getId(), c.getStudentId(), c.getProgramId(), c.getTermId(), c.getStage(), c.getOrigin(),
                null, null, null, c.getOpenedAt(), null, null, List.of(), List.of(), List.of());
    }

    private RetentionCase openInternal(StudentInfo s, RetentionCase.Origin origin, UUID counselor, UUID openedBy) {
        Instant now = clock.instant();
        UUID assigned = counselor != null ? counselor
                : users.activeUsersWithRole(Role.CONSEJERO, s.programId()).stream()
                .min(Comparator.<UUID>comparingLong(u -> cases.countByAssignedCounselorIdAndStageNot(u,
                        RetentionCase.Stage.CERRADO)).thenComparing(UUID::toString)).orElse(null);
        UUID term = records.snapshot(s.id(), null).termId();
        RetentionCase opened = cases.save(RetentionCase.open(s.id(), s.programId(), term, origin, assigned, openedBy, now));
        events.publishEvent(new co.edu.retencion.retention.RetentionCaseChanged(opened.getId(), opened.getStudentId()));
        return opened;
    }

    // ------------------------------------------------------------------ gestión

    @Transactional(readOnly = true)
    public CaseView get(UUID caseId) {
        AuthenticatedUser user = CurrentUser.require();
        return view(load(caseId, AccessAction.READ), user);
    }

    @Transactional(readOnly = true)
    public List<CaseView> ofStudent(UUID studentId) {
        AuthenticatedUser user = CurrentUser.require();
        return cases.findByStudentIdOrderByOpenedAtDesc(studentId).stream().map(c -> view(c, user)).toList();
    }

    @Transactional
    public CaseView advance(UUID caseId, RetentionCase.Stage target) {
        RetentionCase c = load(caseId, AccessAction.WRITE);
        Instant now = clock.instant();
        c.advanceTo(target, contacts.existsByCaseIdAndResult(caseId, ContactAttempt.Result.CONTACTADO),
                tasks.countByCaseId(caseId) > 0, now);
        if (target == RetentionCase.Stage.SEGUIMIENTO) {
            LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
            for (int days : FOLLOW_UP_DAYS) {
                followUps.save(FollowUp.schedule(caseId, FollowUp.Kind.SEGUIMIENTO, days, today.plusDays(days)));
            }
        }
        events.publishEvent(new co.edu.retencion.retention.RetentionCaseChanged(caseId, c.getStudentId()));
        return view(cases.save(c), CurrentUser.require());
    }

    @Transactional
    public ContactView recordContact(UUID caseId, ContactAttempt.Channel channel, ContactAttempt.Result result,
                                     String notes) {
        RetentionCase c = load(caseId, AccessAction.WRITE);
        requireOpen(c);
        ContactAttempt a = contacts.save(ContactAttempt.of(caseId, channel, result, notes, CurrentUser.require().userId(),
                clock.instant()));
        return new ContactView(a.getId(), a.getChannel(), a.getResult(), a.getNotes(), a.getAttemptedBy(),
                a.getAttemptedAt());
    }

    @Transactional
    public NoteView addInterviewNote(UUID caseId, String text) {
        RetentionCase c = load(caseId, AccessAction.WRITE);
        requireInterviewAccess(c, CurrentUser.require());
        InterviewNote n = interviews.save(InterviewNote.write(caseId, CurrentUser.require().userId(), text,
                clock.instant()));
        return new NoteView(n.getId(), n.getAuthorId(), n.getText(), n.getCreatedAt());
    }

    /** Notas de entrevista: solo el consejero asignado y PSICOLOGO (ni coordinación ni docentes). */
    @Transactional(readOnly = true)
    public List<NoteView> interviewNotes(UUID caseId) {
        RetentionCase c = load(caseId, AccessAction.READ);
        requireInterviewAccess(c, CurrentUser.require());
        return interviews.findByCaseIdOrderByCreatedAtAsc(caseId).stream()
                .map(n -> new NoteView(n.getId(), n.getAuthorId(), n.getText(), n.getCreatedAt())).toList();
    }

    @Transactional
    public TaskView addTask(UUID caseId, String title, LocalDate dueDate) {
        RetentionCase c = load(caseId, AccessAction.WRITE);
        requireOpen(c);
        CaseTask t = tasks.save(CaseTask.of(caseId, title, dueDate, CurrentUser.require().userId(), clock.instant()));
        return new TaskView(t.getId(), t.getTitle(), t.getDueDate(), t.getDoneAt());
    }

    @Transactional
    public TaskView completeTask(UUID caseId, UUID taskId) {
        load(caseId, AccessAction.WRITE);
        CaseTask t = tasks.findById(taskId).filter(x -> x.getCaseId().equals(caseId))
                .orElseThrow(() -> DomainException.notFound("Tarea no encontrada"));
        t.done(clock.instant());
        tasks.save(t);
        return new TaskView(t.getId(), t.getTitle(), t.getDueDate(), t.getDoneAt());
    }

    @Transactional
    public FollowUpView completeFollowUp(UUID caseId, UUID followUpId) {
        load(caseId, AccessAction.WRITE);
        FollowUp f = followUps.findById(followUpId).filter(x -> x.getCaseId().equals(caseId))
                .orElseThrow(() -> DomainException.notFound("Seguimiento no encontrado"));
        f.done(clock.instant());
        followUps.save(f);
        return new FollowUpView(f.getId(), f.getKind(), f.getOffsetDays(), f.getDueOn(), f.getStatus(), f.getRemindedAt());
    }

    /**
     * Cierre con resultado. RETIRADO y TRANSFERIDO registran la causa en el catálogo del estudiante y
     * pasan su estado a RETIRADO; APLAZADO lo pasa a APLAZADO (reserva de cupo). El controlador audita.
     */
    @Transactional
    public CaseView close(UUID caseId, RetentionCase.Outcome outcome, String causeCode, Map<String, Object> survey) {
        AuthenticatedUser user = CurrentUser.require();
        RetentionCase c = load(caseId, AccessAction.WRITE);
        if ((outcome == RetentionCase.Outcome.RETIRADO || outcome == RetentionCase.Outcome.TRANSFERIDO)
                && !causes.isActiveCause(causeCode)) {
            throw DomainException.badRequest("La causa no existe en el catálogo o está inactiva");
        }
        c.close(outcome, causeCode, survey == null || survey.isEmpty() ? null : json(survey), clock.instant());
        followUps.findByCaseIdOrderByDueOnAsc(caseId).forEach(f -> {
            f.cancel();
            followUps.save(f);
        });
        switch (outcome) {
            case RETIRADO, TRANSFERIDO -> {
                causes.recordExitCause(c.getStudentId(), causeCode, user.userId());
                students.changeStatus(c.getStudentId(), StudentStatus.RETIRADO);
            }
            case APLAZADO -> students.changeStatus(c.getStudentId(), StudentStatus.APLAZADO);
            case RETENIDO -> {
                // El estudiante sigue ACTIVO.
            }
        }
        events.publishEvent(new co.edu.retencion.retention.RetentionCaseChanged(caseId, c.getStudentId()));
        return view(cases.save(c), user);
    }

    // ------------------------------------------------------------------ recordatorios y embudo

    /** Recordatorios de seguimientos vencidos (una sola vez cada uno). */
    @Transactional
    public int remindDueFollowUps() {
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        int sent = 0;
        for (FollowUp f : followUps.findByStatusAndDueOnLessThanEqualAndRemindedAtIsNull(FollowUp.Status.PENDIENTE, today)) {
            Optional<RetentionCase> c = cases.findById(f.getCaseId());
            if (c.isEmpty() || c.get().getAssignedCounselorId() == null) {
                continue;
            }
            f.reminded(clock.instant());
            followUps.save(f);
            events.publishEvent(new RetentionFollowUpDue(c.get().getId(), c.get().getStudentId(),
                    c.get().getAssignedCounselorId(), f.getKind().name(), f.getOffsetDays()));
            sent++;
        }
        return sent;
    }

    @Transactional(readOnly = true)
    public FunnelView funnel(UUID programId, UUID termId) {
        AuthenticatedUser user = CurrentUser.require();
        boolean global = user.hasRole(Role.ADMIN) || user.programIds().isEmpty();
        Set<UUID> programs;
        if (programId != null) {
            if (!global && !user.programIds().contains(programId)) {
                throw new AccessDeniedException("Programa fuera de tu alcance");
            }
            programs = Set.of(programId);
        } else {
            programs = global ? Set.of(UUID.randomUUID()) : user.programIds();
        }
        boolean anyProgram = programId == null && global;
        List<RetentionCase> rows = cases.funnel(anyProgram, programs, termId == null, termId);
        Map<RetentionCase.Outcome, Long> byOutcome = new EnumMap<>(RetentionCase.Outcome.class);
        rows.stream().filter(c -> c.getOutcome() != null).forEach(c -> byOutcome.merge(c.getOutcome(), 1L, Long::sum));
        return new FunnelView(programId, termId, rows.size(), rows.stream().filter(c -> c.getContactedAt() != null).count(),
                rows.stream().filter(RetentionCase::isOpen).count(), byOutcome.getOrDefault(RetentionCase.Outcome.RETENIDO, 0L),
                byOutcome.getOrDefault(RetentionCase.Outcome.APLAZADO, 0L),
                byOutcome.getOrDefault(RetentionCase.Outcome.TRANSFERIDO, 0L),
                byOutcome.getOrDefault(RetentionCase.Outcome.RETIRADO, 0L));
    }

    // ------------------------------------------------------------------ auxiliares

    private RetentionCase load(UUID caseId, AccessAction action) {
        RetentionCase c = cases.findById(caseId).orElseThrow(() -> DomainException.notFound("Caso no encontrado"));
        AuthenticatedUser user = CurrentUser.require();
        if (!user.userId().equals(c.getAssignedCounselorId()) && !scope.canAccess("STUDENT", c.getStudentId(), action)) {
            throw DomainException.notFound("Caso no encontrado");
        }
        return c;
    }

    /**
     * Serializa la apertura de casos de un mismo estudiante: el análisis automático (listener en otro
     * hilo), el autorreporte y el consejero pueden llegar a la vez y solo puede quedar un caso abierto.
     */
    private void lockStudent(UUID studentId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null, "retention:" + studentId);
    }

    private static void requireOpen(RetentionCase c) {
        if (!c.isOpen()) {
            throw DomainException.conflict("El caso está cerrado");
        }
    }

    private static void requireInterviewAccess(RetentionCase c, AuthenticatedUser user) {
        boolean assigned = user.userId().equals(c.getAssignedCounselorId());
        if (!assigned && !user.hasRole(Role.PSICOLOGO)) {
            throw new AccessDeniedException("Las notas de entrevista son restringidas");
        }
    }

    private CaseView view(RetentionCase c, AuthenticatedUser user) {
        UUID id = c.getId();
        return new CaseView(id, c.getStudentId(), c.getProgramId(), c.getTermId(), c.getStage(), c.getOrigin(),
                c.getAssignedCounselorId(), c.getOutcome(), c.getExitCauseCode(), c.getOpenedAt(), c.getContactedAt(),
                c.getClosedAt(),
                contacts.findByCaseIdOrderByAttemptedAtAsc(id).stream().map(a -> new ContactView(a.getId(), a.getChannel(),
                        a.getResult(), a.getNotes(), a.getAttemptedBy(), a.getAttemptedAt())).toList(),
                tasks.findByCaseIdOrderByDueDateAsc(id).stream().map(t -> new TaskView(t.getId(), t.getTitle(),
                        t.getDueDate(), t.getDoneAt())).toList(),
                followUps.findByCaseIdOrderByDueOnAsc(id).stream().map(f -> new FollowUpView(f.getId(), f.getKind(),
                        f.getOffsetDays(), f.getDueOn(), f.getStatus(), f.getRemindedAt())).toList());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw DomainException.badRequest("Encuesta inválida");
        }
    }
}
