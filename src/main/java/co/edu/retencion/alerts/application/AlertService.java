package co.edu.retencion.alerts.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.Limit;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.alerts.AlertAssigned;
import co.edu.retencion.alerts.domain.Alert;
import co.edu.retencion.alerts.domain.AlertRepository;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.AuthenticatedUser;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.common.web.CursorPage;
import co.edu.retencion.records.AttendanceThresholdReached;
import co.edu.retencion.records.SubjectLostByAbsences;
import co.edu.retencion.risk.RiskAssessed;
import co.edu.retencion.risk.RiskQueries;
import co.edu.retencion.settings.SettingsProvider;
import co.edu.retencion.students.StudentInfo;
import co.edu.retencion.students.StudentRegistry;
import co.edu.retencion.users.UserDirectory;

/**
 * Genera, asigna, escala y cierra alertas.
 *
 * <ul>
 *   <li>Riesgo: solo si el nivel SUBE (o es el primer análisis no BAJO).</li>
 *   <li>Asistencia y pérdida: una alerta por estudiante y grupo.</li>
 *   <li>Deduplicación: con una alerta abierta del mismo tipo, la señal la actualiza; solo se vuelve a
 *       avisar si el nivel subió.</li>
 *   <li>Asignación: a bienestar (PSICOLOGO) si hay factores de bienestar/salud; si no, al consejero del
 *       programa con menos alertas abiertas; sin consejero, a la coordinación.</li>
 *   <li>Escalamiento: sin atender en {@code alerts.escalation-hours} → coordinación del programa.</li>
 * </ul>
 */
@Service
public class AlertService implements co.edu.retencion.alerts.AlertQueries {

    static final Set<Alert.Status> OPEN = EnumSet.of(Alert.Status.NUEVA, Alert.Status.VISTA);

    private final AlertRepository alerts;
    private final RiskQueries risk;
    private final StudentRegistry students;
    private final UserDirectory users;
    private final SettingsProvider settings;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AlertService(AlertRepository alerts, RiskQueries risk, StudentRegistry students, UserDirectory users,
                        SettingsProvider settings, ApplicationEventPublisher events, ObjectMapper mapper, Clock clock,
                        JdbcTemplate jdbc) {
        this.alerts = alerts;
        this.jdbc = jdbc;
        this.risk = risk;
        this.students = students;
        this.users = users;
        this.settings = settings;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public long openCount(java.util.Set<UUID> programIds) {
        return alerts.countOpen(programIds == null, programIds == null ? java.util.Set.of(new UUID(0, 0)) : programIds);
    }

    /** Vista de una alerta para el personal (factores filtrados por rol). */
    public record AlertView(UUID id, UUID studentId, String anonCode, UUID programId, int semester, UUID groupId,
                            Alert.Kind kind, String level, String attendanceLevel, BigDecimal score, String scoreType,
                            List<Map<String, Object>> factors, List<Map<String, Object>> suggestedActions,
                            Alert.Status status, UUID assignedTo, Alert.AssigneeRole assignedRole, int escalationLevel,
                            Instant createdAt, Instant resolvedAt) {
    }

    // ------------------------------------------------------------------ eventos

    @ApplicationModuleListener
    void on(RiskAssessed event) {
        if (!event.escalated() || "BAJO".equals(event.level().name())) {
            return;
        }
        Optional<RiskQueries.AssessmentSummary> summary = risk.summary(event.assessmentId());
        Optional<StudentInfo> student = students.findById(event.studentId());
        if (summary.isEmpty() || student.isEmpty()) {
            return;
        }
        RiskQueries.AssessmentSummary s = summary.get();
        boolean sensitive = s.factors().stream().anyMatch(f -> "BIENESTAR".equals(f.category()));
        raise(new Alert.Signal(event.studentId(), student.get().programId(), student.get().currentSemester(), null,
                Alert.Kind.RIESGO, s.level().name(), null, s.score(), s.scoreType().name(), s.assessmentId(),
                json(s.factors()), json(s.suggestedActions()), sensitive));
    }

    @ApplicationModuleListener
    void on(AttendanceThresholdReached event) {
        attendance(event.studentId(), event.groupId(), Alert.Kind.ASISTENCIA, event.level().name(),
                event.countedAbsences(), event.maxAbsences());
    }

    @ApplicationModuleListener
    void on(SubjectLostByAbsences event) {
        attendance(event.studentId(), event.groupId(), Alert.Kind.PERDIDA, "PERDIDA", event.countedAbsences(),
                event.maxAbsences());
    }

    private void attendance(UUID studentId, UUID groupId, Alert.Kind kind, String attendanceLevel, int absences,
                            int max) {
        Optional<StudentInfo> student = students.findById(studentId);
        if (student.isEmpty()) {
            return;
        }
        Optional<RiskQueries.LatestRisk> latest = risk.latest(studentId);
        String level = "PERDIDA".equals(attendanceLevel) || "CRITICO".equals(attendanceLevel) ? "ALTO" : "MEDIO";
        if (latest.isPresent() && rank(latest.get().level().name()) > rank(level)) {
            level = latest.get().level().name();
        }
        List<Map<String, Object>> factors = List.of(Map.of("code", "ABSENCES", "category", "ASISTENCIA",
                "explanation", absences + " de " + max + " faltas", "details",
                Map.of("absences", absences, "maxAbsences", max, "attendanceLevel", attendanceLevel)));
        raise(new Alert.Signal(studentId, student.get().programId(), student.get().currentSemester(), groupId, kind,
                level, attendanceLevel, latest.map(RiskQueries.LatestRisk::score).orElse(null),
                latest.map(l -> l.scoreType().name()).orElse(null),
                latest.map(RiskQueries.LatestRisk::assessmentId).orElse(null), json(factors), "[]", false));
    }

    /**
     * Crea la alerta o actualiza la abierta (deduplicación). Los listeners corren en paralelo (Spring
     * Modulith habilita {@code @EnableAsync}): el bloqueo consultivo por clave serializa dos eventos
     * del mismo estudiante para que no choquen en el índice único y se pierda la actualización.
     */
    @Transactional
    public Alert raise(Alert.Signal signal) {
        Instant now = clock.instant();
        String key = Alert.dedupeKey(signal);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null, "alert:" + key);
        Optional<Alert> open = alerts.findOpenByKey(key);
        if (open.isPresent()) {
            Alert alert = open.get();
            boolean raised = alert.refresh(signal, now);
            if (raised && alert.getAssignedTo() != null) {
                events.publishEvent(new AlertAssigned(alert.getId(), alert.getStudentId(), alert.getAssignedTo(),
                        alert.getLevel(), alert.getKind().name(), false));
            }
            return alerts.save(alert);
        }
        Alert alert = Alert.open(signal, now);
        assign(alert, now);
        alerts.save(alert);
        if (alert.getAssignedTo() != null) {
            events.publishEvent(new AlertAssigned(alert.getId(), alert.getStudentId(), alert.getAssignedTo(),
                    alert.getLevel(), alert.getKind().name(), false));
        }
        return alert;
    }

    private void assign(Alert alert, Instant now) {
        if (alert.isSensitive()) {
            Optional<UUID> psychologist = leastLoaded(users.activeUsersWithRole(Role.PSICOLOGO, alert.getProgramId()));
            if (psychologist.isPresent()) {
                alert.assign(psychologist.get(), Alert.AssigneeRole.PSICOLOGO, now);
                return;
            }
        }
        Optional<UUID> counselor = leastLoaded(users.activeUsersWithRole(Role.CONSEJERO, alert.getProgramId()));
        if (counselor.isPresent()) {
            alert.assign(counselor.get(), Alert.AssigneeRole.CONSEJERO, now);
            return;
        }
        leastLoaded(users.activeUsersWithRole(Role.COORDINADOR, alert.getProgramId()))
                .ifPresent(c -> alert.assign(c, Alert.AssigneeRole.COORDINADOR, now));
    }

    private Optional<UUID> leastLoaded(List<UUID> candidates) {
        return candidates.stream().min(Comparator.<UUID>comparingLong(u -> alerts.countByAssignedToAndStatusIn(u, OPEN))
                .thenComparing(UUID::toString));
    }

    // ------------------------------------------------------------------ escalamiento

    /** Escala las alertas abiertas sin atender en el plazo configurado; devuelve cuántas escaló. */
    @Scheduled(cron = "${app.alerts.escalation-cron:0 0 * * * *}", zone = "America/Bogota")
    @Transactional
    public int escalateOverdue() {
        Duration hours = Duration.ofHours(Math.max(1, settings.getInt("alerts.escalation-hours", 48)));
        Instant now = clock.instant();
        int escalated = 0;
        for (Alert alert : alerts.findOpen()) {
            if (!alert.overdue(hours, now)) {
                continue;
            }
            UUID coordinator = leastLoaded(users.activeUsersWithRole(Role.COORDINADOR, alert.getProgramId()).stream()
                    .filter(c -> !c.equals(alert.getAssignedTo())).toList()).orElse(null);
            alert.escalate(coordinator, now);
            alerts.save(alert);
            if (coordinator != null) {
                events.publishEvent(new AlertAssigned(alert.getId(), alert.getStudentId(), coordinator,
                        alert.getLevel(), alert.getKind().name(), true));
            }
            escalated++;
        }
        return escalated;
    }

    // ------------------------------------------------------------------ consultas y cierre

    @Transactional(readOnly = true)
    public CursorPage<AlertView> list(Alert.Status status, boolean onlyMine, UUID after, Integer size) {
        AuthenticatedUser user = CurrentUser.require();
        boolean global = user.hasRole(Role.ADMIN) || (user.programIds().isEmpty() && user.hasRole(Role.PSICOLOGO));
        boolean mine = onlyMine || !(global || user.hasRole(Role.COORDINADOR) || user.hasRole(Role.CONSEJERO));
        int limit = CursorPage.clampSize(size);
        Set<UUID> programs = user.programIds().isEmpty() ? Set.of(UUID.randomUUID()) : user.programIds();
        List<AlertView> rows = alerts.search(mine, user.userId(), global || mine, programs, status == null, status,
                after == null, after, Limit.of(limit + 1)).stream().map(a -> view(a, user)).toList();
        return CursorPage.of(rows, limit, AlertView::id);
    }

    @Transactional
    public AlertView open(UUID id) {
        AuthenticatedUser user = CurrentUser.require();
        Alert alert = load(id, user);
        if (user.userId().equals(alert.getAssignedTo())) {
            alert.markSeen(clock.instant());
        }
        return view(alerts.save(alert), user);
    }

    @Transactional
    public AlertView close(UUID id, boolean attended, String note) {
        AuthenticatedUser user = CurrentUser.require();
        Alert alert = load(id, user);
        alert.close(attended, user.userId(), note, clock.instant());
        return view(alerts.save(alert), user);
    }

    @Transactional(readOnly = true)
    public List<AlertView> ofStudent(UUID studentId) {
        AuthenticatedUser user = CurrentUser.require();
        return alerts.findByStudentIdOrderByIdDesc(studentId).stream().filter(a -> visible(a, user))
                .map(a -> view(a, user)).toList();
    }

    private Alert load(UUID id, AuthenticatedUser user) {
        return alerts.findById(id).filter(a -> visible(a, user))
                .orElseThrow(() -> DomainException.notFound("Alerta no encontrada"));
    }

    /** Responsable, ADMIN, o personal del programa (bienestar solo si la alerta es sensible o no tiene programas). */
    static boolean visible(Alert a, AuthenticatedUser user) {
        if (user.hasRole(Role.ADMIN) || user.userId().equals(a.getAssignedTo())) {
            return true;
        }
        boolean inProgram = user.programIds().isEmpty() || user.programIds().contains(a.getProgramId());
        if (user.hasRole(Role.PSICOLOGO)) {
            return inProgram;
        }
        return (user.hasRole(Role.CONSEJERO) || user.hasRole(Role.COORDINADOR)) && !user.programIds().isEmpty()
                && user.programIds().contains(a.getProgramId());
    }

    private AlertView view(Alert a, AuthenticatedUser user) {
        boolean wellbeing = user.hasRole(Role.PSICOLOGO);
        boolean socio = wellbeing || user.hasRole(Role.ADMIN) || user.hasRole(Role.CONSEJERO);
        List<Map<String, Object>> factors = read(a.getFactors()).stream()
                .filter(f -> wellbeing || !"BIENESTAR".equals(f.get("category")))
                .filter(f -> socio || !"SOCIOECONOMICO".equals(f.get("category"))).toList();
        // Las acciones de la IA pueden aludir a señales sensibles: con contexto sensible solo bienestar las ve.
        List<Map<String, Object>> actions = a.isSensitive() && !wellbeing ? List.of() : read(a.getSuggestedActions());
        String anon = students.findById(a.getStudentId()).map(StudentInfo::anonCode).orElse(null);
        return new AlertView(a.getId(), a.getStudentId(), anon, a.getProgramId(), a.getSemester(), a.getGroupId(),
                a.getKind(), a.getLevel(), a.getAttendanceLevel(), a.getScore(), a.getScoreType(), factors, actions,
                a.getStatus(), a.getAssignedTo(), a.getAssignedRole(), a.getEscalationLevel(), a.getCreatedAt(),
                a.getResolvedAt());
    }

    private static int rank(String level) {
        return switch (level) {
            case "ALTO" -> 3;
            case "MEDIO" -> 2;
            default -> 1;
        };
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private List<Map<String, Object>> read(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }
}
