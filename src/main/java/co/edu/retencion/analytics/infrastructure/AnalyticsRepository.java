package co.edu.retencion.analytics.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import co.edu.retencion.analytics.AnalyticsFilter;
import co.edu.retencion.analytics.AnalyticsQueries.Granularity;
import co.edu.retencion.analytics.AnalyticsViewer;
import co.edu.retencion.analytics.domain.TrendMerger;

/**
 * Consultas sobre el modelo de lectura. Solo toca las vistas {@code analytics_*} y {@code dropout_history}:
 * los demás módulos siguen siendo dueños de sus tablas. Todo valor va como parámetro; lo único que se
 * concatena son fragmentos fijos de este archivo.
 */
@Repository
public class AnalyticsRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AnalyticsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Conteos crudos (antes de suprimir) por programa. */
    public record ProgramCount(UUID programId, long students, long dropouts) {
    }

    public record SemesterCount(int semester, long students, long dropouts, long atRisk) {
    }

    public record SubjectCount(UUID subjectId, String code, String name, long enrollments, long absences, long warning,
                               long critical, long lost) {
    }

    public record LevelTotals(long warning, long critical, long lost) {
    }

    public record FunnelCount(long detected, long contacted, long inProgress, long retained, long postponed,
                              long transferred, long withdrawn) {
    }

    public record OverviewCount(long students, long active, long high, long medium, long low, long withdrawn) {
    }

    /** Condiciones comunes; las columnas se llaman igual en las vistas. */
    private record Where(String sql, MapSqlParameterSource params) {
    }

    private static Where where(AnalyticsFilter f, AnalyticsViewer viewer, boolean hasTermDates) {
        StringBuilder sql = new StringBuilder(" WHERE true");
        MapSqlParameterSource params = new MapSqlParameterSource();
        Set<UUID> programs = viewer.effective(f.programId());
        if (programs != null) {
            sql.append(" AND program_id IN (:programs)");
            params.addValue("programs", List.copyOf(programs));
        }
        if (f.semester() != null) {
            sql.append(" AND semester = :semester");
            params.addValue("semester", f.semester());
        }
        if (f.termId() != null) {
            sql.append(" AND term_id = :term");
            params.addValue("term", f.termId());
        }
        if (f.year() != null) {
            sql.append(" AND term_year = :year");
            params.addValue("year", f.year());
        }
        if (f.riskLevel() != null) {
            sql.append(" AND risk_level = :risk");
            params.addValue("risk", f.riskLevel().name());
        }
        if (hasTermDates && f.from() != null) {
            sql.append(" AND term_end >= :from");
            params.addValue("from", f.from());
        }
        if (hasTermDates && f.to() != null) {
            sql.append(" AND term_start <= :to");
            params.addValue("to", f.to());
        }
        return new Where(sql.toString(), params);
    }

    /** Retiros dentro del rango de fechas pedido. */
    private static String dropoutCondition(AnalyticsFilter f, MapSqlParameterSource params) {
        StringBuilder sql = new StringBuilder("is_dropout");
        if (f.from() != null) {
            sql.append(" AND withdrawn_on >= :from");
            params.addValue("from", f.from());
        }
        if (f.to() != null) {
            sql.append(" AND withdrawn_on <= :to");
            params.addValue("to", f.to());
        }
        return sql.toString();
    }

    public List<ProgramCount> dropoutByProgram(AnalyticsFilter f, AnalyticsViewer viewer) {
        Where w = where(f, viewer, true);
        String dropout = dropoutCondition(f, w.params());
        return jdbc.query("SELECT program_id, count(DISTINCT student_id) AS students, count(DISTINCT student_id) FILTER (WHERE "
                        + dropout + ") AS dropouts FROM analytics_student_facts" + w.sql() + " GROUP BY program_id",
                w.params(), (rs, i) -> new ProgramCount(rs.getObject("program_id", UUID.class), rs.getLong("students"),
                        rs.getLong("dropouts")));
    }

    public List<SemesterCount> bySemester(AnalyticsFilter f, AnalyticsViewer viewer) {
        Where w = where(f, viewer, true);
        String dropout = dropoutCondition(f, w.params());
        return jdbc.query("SELECT semester, count(DISTINCT student_id) AS students, count(DISTINCT student_id) FILTER (WHERE "
                        + dropout + ") AS dropouts, count(DISTINCT student_id) FILTER (WHERE status = 'ACTIVO' AND risk_level "
                        + "IN ('ALTO', 'MEDIO')) AS at_risk FROM analytics_student_facts" + w.sql()
                        + " GROUP BY semester ORDER BY semester",
                w.params(), (rs, i) -> new SemesterCount(rs.getInt("semester"), rs.getLong("students"),
                        rs.getLong("dropouts"), rs.getLong("at_risk")));
    }

    /** Estudiantes activos por nivel. */
    public Map<String, Long> riskDistribution(AnalyticsFilter f, AnalyticsViewer viewer) {
        Where w = where(f, viewer, true);
        Map<String, Long> levels = new java.util.HashMap<>();
        jdbc.query("SELECT risk_level, count(DISTINCT student_id) AS students FROM analytics_student_facts" + w.sql()
                + " AND status = 'ACTIVO' GROUP BY risk_level", w.params(), (ResultSet rs) -> {
                    levels.put(rs.getString("risk_level"), rs.getLong("students"));
                });
        return levels;
    }

    public List<TrendMerger.Point> liveTrend(AnalyticsFilter f, Granularity granularity, AnalyticsViewer viewer) {
        Where w = where(f, viewer, true);
        String dropout = dropoutCondition(f, w.params());
        String unit = granularity == Granularity.YEAR ? "year" : "month";
        List<TrendMerger.Point> dropouts = jdbc.query("SELECT date_trunc('" + unit + "', withdrawn_on)::date AS period, "
                        + "count(DISTINCT student_id) AS dropouts FROM analytics_student_facts" + w.sql() + " AND " + dropout
                        + " GROUP BY 1",
                w.params(), (rs, i) -> new TrendMerger.Point(rs.getObject("period", LocalDate.class), rs.getLong("dropouts"),
                        null));
        if (granularity != Granularity.YEAR) {
            return dropouts;
        }
        // Por año sí se conoce la población: estudiantes con inscripción en periodos de ese año.
        Map<LocalDate, Long> enrolled = new java.util.HashMap<>();
        jdbc.query("SELECT make_date(term_year, 1, 1) AS period, count(DISTINCT student_id) AS students "
                + "FROM analytics_student_facts" + w.sql() + " GROUP BY 1", w.params(), (ResultSet rs) -> {
                    enrolled.put(rs.getObject("period", LocalDate.class), rs.getLong("students"));
                });
        return dropouts.stream().map(p -> new TrendMerger.Point(p.period(), p.dropouts(), enrolled.get(p.period()))).toList();
    }

    /**
     * Serie histórica. Se corta antes del primer periodo con datos en vivo para no contar dos veces. No
     * tiene nivel de riesgo ni periodo: con esos filtros no aplica.
     */
    public List<TrendMerger.Point> historicalTrend(AnalyticsFilter f, Granularity granularity, AnalyticsViewer viewer) {
        if (f.riskLevel() != null || f.termId() != null) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(" WHERE true");
        MapSqlParameterSource params = new MapSqlParameterSource();
        Set<UUID> programs = viewer.effective(f.programId());
        if (programs != null) {
            sql.append(" AND program_id IN (:programs)");
            params.addValue("programs", List.copyOf(programs));
        }
        if (f.semester() != null) {
            sql.append(" AND semester = :semester");
            params.addValue("semester", f.semester());
        }
        if (f.year() != null) {
            sql.append(" AND EXTRACT(YEAR FROM period_month) = :year");
            params.addValue("year", f.year());
        }
        if (f.from() != null) {
            sql.append(" AND period_month >= date_trunc('month', CAST(:from AS date))");
            params.addValue("from", f.from());
        }
        if (f.to() != null) {
            sql.append(" AND period_month <= :to");
            params.addValue("to", f.to());
        }
        LocalDate cutoff = jdbc.queryForObject("SELECT date_trunc('month', min(term_start))::date FROM analytics_student_facts",
                Map.of(), LocalDate.class);
        if (cutoff != null) {
            sql.append(" AND period_month < :cutoff");
            params.addValue("cutoff", cutoff);
        }
        String unit = granularity == Granularity.YEAR ? "year" : "month";
        return jdbc.query("SELECT date_trunc('" + unit + "', period_month)::date AS period, sum(dropouts) AS dropouts, "
                        + "sum(enrolled) AS enrolled FROM dropout_history" + sql + " GROUP BY 1",
                params, (rs, i) -> new TrendMerger.Point(rs.getObject("period", LocalDate.class), rs.getLong("dropouts"),
                        rs.getLong("enrolled")));
    }

    public List<SubjectCount> subjects(AnalyticsFilter f, int limit, AnalyticsViewer viewer) {
        Where w = where(f, viewer, true);
        w.params().addValue("limit", limit);
        return jdbc.query("SELECT subject_id, subject_code, subject_name, sum(enrollments) AS enrollments, "
                        + "sum(absences) AS absences, sum(aviso) AS aviso, sum(critico) AS critico, sum(perdida) AS perdida "
                        + "FROM analytics_attendance_cube" + w.sql()
                        + " GROUP BY subject_id, subject_code, subject_name ORDER BY sum(absences) DESC, subject_code LIMIT :limit",
                w.params(), (rs, i) -> new SubjectCount(rs.getObject("subject_id", UUID.class), rs.getString("subject_code"),
                        rs.getString("subject_name"), rs.getLong("enrollments"), rs.getLong("absences"), rs.getLong("aviso"),
                        rs.getLong("critico"), rs.getLong("perdida")));
    }

    public LevelTotals levelTotals(AnalyticsFilter f, AnalyticsViewer viewer) {
        Where w = where(f, viewer, true);
        return jdbc.queryForObject("SELECT COALESCE(sum(aviso), 0) AS aviso, COALESCE(sum(critico), 0) AS critico, "
                        + "COALESCE(sum(perdida), 0) AS perdida FROM analytics_attendance_cube" + w.sql(),
                w.params(), (rs, i) -> new LevelTotals(rs.getLong("aviso"), rs.getLong("critico"), rs.getLong("perdida")));
    }

    public FunnelCount funnel(AnalyticsFilter f, AnalyticsViewer viewer) {
        Where w = where(f, viewer, false);
        StringBuilder sql = new StringBuilder(w.sql());
        if (f.from() != null) {
            sql.append(" AND opened_month >= date_trunc('month', CAST(:from AS date))");
            w.params().addValue("from", f.from());
        }
        if (f.to() != null) {
            sql.append(" AND opened_month <= :to");
            w.params().addValue("to", f.to());
        }
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(cases), 0) AS detected,
                       COALESCE(sum(cases) FILTER (WHERE contacted), 0) AS contacted,
                       COALESCE(sum(cases) FILTER (WHERE in_progress), 0) AS in_progress,
                       COALESCE(sum(cases) FILTER (WHERE outcome = 'RETENIDO'), 0) AS retained,
                       COALESCE(sum(cases) FILTER (WHERE outcome = 'APLAZADO'), 0) AS postponed,
                       COALESCE(sum(cases) FILTER (WHERE outcome = 'TRANSFERIDO'), 0) AS transferred,
                       COALESCE(sum(cases) FILTER (WHERE outcome = 'RETIRADO'), 0) AS withdrawn
                FROM analytics_retention_cube
                """ + sql, w.params(), (rs, i) -> new FunnelCount(rs.getLong("detected"), rs.getLong("contacted"),
                rs.getLong("in_progress"), rs.getLong("retained"), rs.getLong("postponed"), rs.getLong("transferred"),
                rs.getLong("withdrawn")));
    }

    public OverviewCount overview(AnalyticsViewer viewer) {
        Where w = where(AnalyticsFilter.none(), viewer, false);
        return jdbc.queryForObject("""
                SELECT count(DISTINCT student_id) AS students,
                       count(DISTINCT student_id) FILTER (WHERE status = 'ACTIVO') AS active,
                       count(DISTINCT student_id) FILTER (WHERE status = 'ACTIVO' AND risk_level = 'ALTO') AS high,
                       count(DISTINCT student_id) FILTER (WHERE status = 'ACTIVO' AND risk_level = 'MEDIO') AS medium,
                       count(DISTINCT student_id) FILTER (WHERE status = 'ACTIVO' AND risk_level = 'BAJO') AS low,
                       count(DISTINCT student_id) FILTER (WHERE is_dropout) AS withdrawn
                FROM analytics_student_facts
                """ + w.sql(), w.params(), (rs, i) -> new OverviewCount(rs.getLong("students"), rs.getLong("active"),
                rs.getLong("high"), rs.getLong("medium"), rs.getLong("low"), rs.getLong("withdrawn")));
    }

    // ------------------------------------------------------------------ estado del refresco

    /** Estado del modelo de lectura. */
    public record RefreshState(boolean dirty, Instant refreshedAt) {
    }

    public RefreshState refreshState() {
        return jdbc.queryForObject("SELECT dirty, refreshed_at FROM analytics_refresh_state", Map.of(),
                (rs, i) -> new RefreshState(rs.getBoolean("dirty"), instant(rs)));
    }

    public Optional<Instant> refreshedAt() {
        return Optional.ofNullable(refreshState().refreshedAt());
    }

    /** Solo escribe la primera vez: los eventos siguientes no compiten por la fila. */
    public void markDirty(Instant now) {
        jdbc.update("UPDATE analytics_refresh_state SET dirty = true, dirty_since = :now WHERE NOT dirty",
                new MapSqlParameterSource("now", java.sql.Timestamp.from(now)));
    }

    /** Refresca las vistas si ninguna otra instancia lo está haciendo. Debe correr en una transacción. */
    public boolean refresh(Instant now) {
        Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended('analytics-refresh', 0))",
                Map.of(), Boolean.class);
        if (!Boolean.TRUE.equals(locked)) {
            return false;
        }
        // Se limpia antes de refrescar: un evento que llegue durante el refresco vuelve a marcar.
        jdbc.update("UPDATE analytics_refresh_state SET dirty = false, dirty_since = NULL, refreshed_at = :now",
                new MapSqlParameterSource("now", java.sql.Timestamp.from(now)));
        jdbc.query("SELECT analytics_refresh()", Map.of(), (ResultSet rs) -> {
        });
        return true;
    }

    private static Instant instant(ResultSet rs) throws SQLException {
        OffsetDateTime value = rs.getObject("refreshed_at", OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
