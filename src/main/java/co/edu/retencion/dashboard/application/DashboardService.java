package co.edu.retencion.dashboard.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.alerts.AlertQueries;
import co.edu.retencion.analytics.AnalyticsQueries;
import co.edu.retencion.analytics.AnalyticsViewer;
import co.edu.retencion.analytics.AnalyticsViews.Overview;
import co.edu.retencion.common.security.AuthenticatedUser;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.common.web.CursorPage;
import co.edu.retencion.notifications.NotificationFeed;
import co.edu.retencion.risk.RiskLevel;
import co.edu.retencion.risk.RiskQueries;
import co.edu.retencion.students.EnrollmentInfo;
import co.edu.retencion.students.StudentInfo;
import co.edu.retencion.students.StudentRegistry;
import co.edu.retencion.students.StudentStatus;

/**
 * Tablero según el rol. El personal ve sus programas (ADMIN todo) con totales del modelo de lectura; el
 * docente ve solo a los estudiantes activos de sus grupos, calculado en vivo porque son pocos, y sin
 * porcentaje de abandono ni alertas, que no son de su alcance.
 */
@Service
public class DashboardService {

    private static final List<RiskLevel> LISTED = List.of(RiskLevel.ALTO, RiskLevel.MEDIO, RiskLevel.BAJO);
    private static final int NOTIFICATIONS = 5;

    private final AnalyticsQueries analytics;
    private final AlertQueries alerts;
    private final RiskQueries risk;
    private final StudentRegistry students;
    private final AcademicDirectory academic;
    private final NotificationFeed notifications;

    public DashboardService(AnalyticsQueries analytics, AlertQueries alerts, RiskQueries risk, StudentRegistry students,
                            AcademicDirectory academic, NotificationFeed notifications) {
        this.analytics = analytics;
        this.alerts = alerts;
        this.risk = risk;
        this.students = students;
        this.academic = academic;
        this.notifications = notifications;
    }

    /**
     * @param openAlerts  {@code null} para el docente
     * @param dropoutRate {@code null} para el docente
     * @param refreshedAt fecha de los totales; {@code null} si se calcularon en vivo
     */
    public record Totals(long students, long atRisk, long high, long medium, long low, Long openAlerts,
                         BigDecimal dropoutRate, Instant refreshedAt) {
    }

    /** Fila de las listas: código anónimo, sin identidad ni factores. */
    public record StudentRow(UUID studentId, String anonCode, UUID programId, int semester, String level, BigDecimal score,
                             Instant assessedAt) {
    }

    /** @param scope {@code GLOBAL}, {@code PROGRAMS} o {@code GROUPS} */
    public record Summary(String scope, Totals totals, Map<String, CursorPage<StudentRow>> lists,
                          List<NotificationFeed.FeedItem> notifications) {
    }

    /** Lo que el usuario puede ver: programas, o estudiantes concretos (docente). */
    private record Reach(String scope, Set<UUID> programs, Set<UUID> students) {
    }

    public Summary summary(AuthenticatedUser user, Integer size) {
        Reach reach = reach(user);
        Map<String, CursorPage<StudentRow>> lists = new LinkedHashMap<>();
        for (RiskLevel level : LISTED) {
            lists.put(level.name(), page(reach, level, null, size));
        }
        return new Summary(reach.scope(), totals(reach), lists, notifications.latest(user.userId(), NOTIFICATIONS));
    }

    public CursorPage<StudentRow> students(AuthenticatedUser user, RiskLevel level, UUID after, Integer size) {
        return page(reach(user), level, after, size);
    }

    private Reach reach(AuthenticatedUser user) {
        boolean staff = user.hasRole(Role.ADMIN) || user.hasRole(Role.CONSEJERO) || user.hasRole(Role.PSICOLOGO)
                || user.hasRole(Role.COORDINADOR);
        if (staff) {
            Set<UUID> programs = AnalyticsViewer.of(user).programs();
            return new Reach(programs == null ? "GLOBAL" : "PROGRAMS", programs, null);
        }
        return new Reach("GROUPS", null, activeStudentsOf(user.userId()));
    }

    private Set<UUID> activeStudentsOf(UUID teacher) {
        Set<UUID> ids = new HashSet<>();
        for (UUID group : academic.groupsTaughtBy(teacher)) {
            students.activeEnrollments(group).stream().map(EnrollmentInfo::studentId).forEach(ids::add);
        }
        Map<UUID, StudentInfo> infos = students.findByIds(ids);
        ids.removeIf(id -> !infos.containsKey(id) || infos.get(id).status() != StudentStatus.ACTIVO);
        return ids;
    }

    private Totals totals(Reach reach) {
        if (reach.students() != null) {
            Map<RiskLevel, Long> counts = reach.students().isEmpty() ? Map.of() : risk.levelCounts(reach.students());
            long high = counts.getOrDefault(RiskLevel.ALTO, 0L);
            long medium = counts.getOrDefault(RiskLevel.MEDIO, 0L);
            return new Totals(reach.students().size(), high + medium, high, medium, counts.getOrDefault(RiskLevel.BAJO, 0L),
                    null, null, null);
        }
        Overview o = analytics.overview(new AnalyticsViewer(reach.programs()));
        return new Totals(o.active(), o.high() + o.medium(), o.high(), o.medium(), o.low(), alerts.openCount(reach.programs()),
                o.dropoutRate(), o.refreshedAt());
    }

    /** Solo estudiantes activos: los retirados conservan su último nivel y se saltan al paginar. */
    private CursorPage<StudentRow> page(Reach reach, RiskLevel level, UUID after, Integer size) {
        int limit = CursorPage.clampSize(size);
        if (reach.students() != null && reach.students().isEmpty()) {
            return new CursorPage<>(List.of(), null);
        }
        List<StudentRow> rows = new ArrayList<>();
        UUID cursor = after;
        while (rows.size() <= limit) {
            List<RiskQueries.RiskRow> batch = risk.page(new RiskQueries.RiskListQuery(EnumSet.of(level), reach.programs(),
                    reach.students(), cursor, limit + 1));
            if (batch.isEmpty()) {
                break;
            }
            Map<UUID, StudentInfo> infos = students.findByIds(ids(batch));
            for (RiskQueries.RiskRow r : batch) {
                StudentInfo info = infos.get(r.studentId());
                if (info != null && info.status() == StudentStatus.ACTIVO) {
                    rows.add(new StudentRow(r.studentId(), info.anonCode(), info.programId(), info.currentSemester(),
                            r.level().name(), r.score(), r.assessedAt()));
                }
            }
            cursor = batch.get(batch.size() - 1).studentId();
            if (batch.size() <= limit) {
                break;
            }
        }
        return CursorPage.of(rows.size() > limit + 1 ? rows.subList(0, limit + 1) : rows, limit, StudentRow::studentId);
    }

    private static Collection<UUID> ids(List<RiskQueries.RiskRow> rows) {
        return rows.stream().map(RiskQueries.RiskRow::studentId).toList();
    }
}
