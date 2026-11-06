package co.edu.retencion.reports.application;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.academic.GroupInfo;
import co.edu.retencion.academic.ProgramInfo;
import co.edu.retencion.analytics.AnalyticsFilter;
import co.edu.retencion.analytics.AnalyticsQueries;
import co.edu.retencion.analytics.AnalyticsViewer;
import co.edu.retencion.analytics.AnalyticsViews;
import co.edu.retencion.common.time.TimeConfig;
import co.edu.retencion.records.StudentRecords;
import co.edu.retencion.reports.domain.ReportDocument;
import co.edu.retencion.reports.domain.ReportJob;
import co.edu.retencion.risk.RiskLevel;
import co.edu.retencion.risk.RiskQueries;
import co.edu.retencion.students.StudentInfo;
import co.edu.retencion.students.StudentRegistry;
import co.edu.retencion.students.StudentStatus;

/**
 * Arma el contenido de cada tipo de reporte con los filtros y el alcance guardados en el trabajo. Los
 * agregados salen de analytics, ya con supresión de celdas. Los listados recorren por keyset y solo
 * llevan código, programa, semestre, nivel y faltas: nunca factores psicológicos, de salud ni
 * socioeconómicos.
 *
 * <p><b>Patrón Factory Method:</b> {@link #build} decide qué documento construir según el tipo.
 */
@Component
class ReportContentFactory {

    private static final int BATCH = 1000;
    private static final int TOP_SUBJECTS = 50;
    private static final String HIDDEN = "Suprimido";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AnalyticsQueries analytics;
    private final RiskQueries risk;
    private final StudentRegistry students;
    private final StudentRecords records;
    private final AcademicDirectory academic;
    private final Clock clock;

    ReportContentFactory(AnalyticsQueries analytics, RiskQueries risk, StudentRegistry students, StudentRecords records,
                         AcademicDirectory academic, Clock clock) {
        this.analytics = analytics;
        this.risk = risk;
        this.students = students;
        this.records = records;
        this.academic = academic;
        this.clock = clock;
    }

    ReportDocument build(ReportJob job, AnalyticsFilter filter, AnalyticsViewer viewer) {
        ReportDocument.Builder b = ReportDocument.builder().title(title(job.getType())).notice(notice(job));
        switch (job.getType()) {
            case GENERAL -> {
                programs(b, filter, viewer);
                semesters(b, filter, viewer);
                riskLevels(b, filter, viewer);
                funnel(b, filter, viewer);
                subjects(b, filter, viewer);
            }
            case BY_PROGRAM -> {
                programs(b, filter, viewer);
                riskLevels(b, filter, viewer);
            }
            case BY_SEMESTER -> semesters(b, filter, viewer);
            case BY_YEAR -> years(b, filter, viewer);
            case AT_RISK_STUDENTS -> atRisk(b, filter, viewer, job.isIncludeIdentity());
            case ATTENDANCE -> {
                subjects(b, filter, viewer);
                nearLimit(b, filter, viewer, job.isIncludeIdentity());
            }
        }
        return b.build();
    }

    private static String title(ReportJob.Type type) {
        return switch (type) {
            case GENERAL -> "Reporte general de retención";
            case BY_PROGRAM -> "Deserción por programa";
            case BY_SEMESTER -> "Deserción por semestre";
            case BY_YEAR -> "Deserción por año";
            case AT_RISK_STUDENTS -> "Estudiantes en riesgo";
            case ATTENDANCE -> "Asistencia";
        };
    }

    private String notice(ReportJob job) {
        String base = "Uso interno. Datos personales protegidos (Ley 1581 de 2012): no reenviar ni publicar. Generado el "
                + STAMP.format(LocalDateTime.now(clock.withZone(TimeConfig.BUSINESS_ZONE))) + " para el usuario "
                + job.getRequestedBy() + ".";
        return job.isIncludeIdentity() ? base + " Contiene identidad de estudiantes." : base;
    }

    private static Object cell(Object value, boolean suppressed) {
        return value == null && suppressed ? HIDDEN : value;
    }

    private void programs(ReportDocument.Builder b, AnalyticsFilter f, AnalyticsViewer v) {
        List<AnalyticsViews.ProgramRow> rows = analytics.dropoutByProgram(f, v).rows();
        b.section("Por programa", List.of("Programa", "Estudiantes", "Desertores", "% deserción"), sink -> rows.forEach(r ->
                sink.accept(row(r.programName(), cell(r.students(), r.suppressed()), cell(r.dropouts(), r.suppressed()),
                        cell(r.dropoutRate(), r.suppressed())))));
    }

    private void semesters(ReportDocument.Builder b, AnalyticsFilter f, AnalyticsViewer v) {
        List<AnalyticsViews.SemesterRow> rows = analytics.bySemester(f, v).rows();
        b.section("Por semestre", List.of("Semestre", "Estudiantes", "Desertores", "En riesgo"), sink -> rows.forEach(r ->
                sink.accept(row(r.semester(), cell(r.students(), r.suppressed()), cell(r.dropouts(), r.suppressed()),
                        cell(r.atRisk(), r.suppressed())))));
    }

    private void years(ReportDocument.Builder b, AnalyticsFilter f, AnalyticsViewer v) {
        List<AnalyticsViews.TrendPoint> rows = analytics.trend(f, AnalyticsQueries.Granularity.YEAR, v).rows();
        b.section("Por año", List.of("Año", "Desertores", "Matriculados", "% deserción", "Origen"), sink -> rows.forEach(r ->
                sink.accept(row(r.period(), cell(r.dropouts(), r.suppressed()), r.enrolled(),
                        cell(r.dropoutRate(), r.suppressed()), r.source()))));
    }

    private void riskLevels(ReportDocument.Builder b, AnalyticsFilter f, AnalyticsViewer v) {
        List<AnalyticsViews.RiskRow> rows = analytics.riskDistribution(f, v).rows();
        b.section("Por nivel de riesgo", List.of("Nivel", "Estudiantes activos", "%"), sink -> rows.forEach(r ->
                sink.accept(row(r.level(), cell(r.students(), r.suppressed()), cell(r.share(), r.suppressed())))));
    }

    private void funnel(ReportDocument.Builder b, AnalyticsFilter f, AnalyticsViewer v) {
        AnalyticsViews.RetentionFunnel r = analytics.retentionFunnel(f, v);
        b.section("Embudo de retención", List.of("Etapa", "Casos"), sink -> {
            sink.accept(row("Detectados", cell(r.detected(), true)));
            sink.accept(row("Contactados", cell(r.contacted(), true)));
            sink.accept(row("En curso", cell(r.inProgress(), true)));
            sink.accept(row("Retenidos", cell(r.retained(), true)));
            sink.accept(row("Aplazados", cell(r.postponed(), true)));
            sink.accept(row("Transferidos", cell(r.transferred(), true)));
            sink.accept(row("Retirados", cell(r.withdrawn(), true)));
        });
    }

    private void subjects(ReportDocument.Builder b, AnalyticsFilter f, AnalyticsViewer v) {
        List<AnalyticsViews.SubjectRow> rows = analytics.attendanceRisk(f, TOP_SUBJECTS, v).subjects();
        b.section("Materias con más faltas", List.of("Materia", "Inscripciones", "Faltas", "Aviso", "Crítico", "Pérdida"),
                sink -> rows.forEach(r -> sink.accept(row(r.subjectName(), cell(r.enrollments(), true),
                        cell(r.absences(), true), cell(r.warning(), true), cell(r.critical(), true), cell(r.lost(), true)))));
    }

    private void atRisk(ReportDocument.Builder b, AnalyticsFilter f, AnalyticsViewer v, boolean identity) {
        Set<RiskLevel> levels = levels(f);
        Set<UUID> programs = v.effective(f.programId());
        Map<UUID, String> codes = programCodes();
        List<String> columns = new ArrayList<>(List.of("Código", "Programa", "Semestre", "Nivel", "Puntaje", "Último análisis"));
        if (identity) {
            columns.add(1, "Nombre");
        }
        b.section("Estudiantes en riesgo", columns, sink -> {
            if (levels.isEmpty()) {
                return;
            }
            UUID after = null;
            while (true) {
                List<RiskQueries.RiskRow> batch = risk.page(new RiskQueries.RiskListQuery(levels, programs, null, after, BATCH));
                if (batch.isEmpty()) {
                    return;
                }
                List<UUID> ids = batch.stream().map(RiskQueries.RiskRow::studentId).toList();
                Map<UUID, StudentInfo> infos = students.findByIds(ids);
                Map<UUID, String> names = identity ? students.displayNames(ids) : Map.of();
                for (RiskQueries.RiskRow r : batch) {
                    StudentInfo s = infos.get(r.studentId());
                    if (s == null || s.status() != StudentStatus.ACTIVO
                            || (f.semester() != null && f.semester() != s.currentSemester())) {
                        continue;
                    }
                    List<Object> values = row(s.anonCode(), codes.get(s.programId()), s.currentSemester(), r.level().name(),
                            r.score(), r.assessedAt() == null ? null : r.assessedAt().toString());
                    if (identity) {
                        values.add(1, names.get(r.studentId()));
                    }
                    sink.accept(values);
                }
                after = ids.get(ids.size() - 1);
                if (batch.size() < BATCH) {
                    return;
                }
            }
        });
    }

    private static Set<RiskLevel> levels(AnalyticsFilter f) {
        if (f.riskLevel() == null) {
            return EnumSet.of(RiskLevel.ALTO, RiskLevel.MEDIO);
        }
        return switch (f.riskLevel()) {
            case ALTO -> EnumSet.of(RiskLevel.ALTO);
            case MEDIO -> EnumSet.of(RiskLevel.MEDIO);
            case BAJO -> EnumSet.of(RiskLevel.BAJO);
            case SIN_ANALISIS -> EnumSet.noneOf(RiskLevel.class);
        };
    }

    private void nearLimit(ReportDocument.Builder b, AnalyticsFilter f, AnalyticsViewer v, boolean identity) {
        Set<UUID> programs = v.effective(f.programId());
        Map<UUID, String> subjectNames = new HashMap<>();
        List<String> columns = new ArrayList<>(List.of("Código", "Materia", "Faltas", "Límite", "Nivel"));
        if (identity) {
            columns.add(1, "Nombre");
        }
        b.section("Cerca del límite de faltas", columns, sink -> forEachNearLimit(f, rows -> {
            List<UUID> ids = rows.stream().map(StudentRecords.NearLimitRow::studentId).distinct().toList();
            Map<UUID, StudentInfo> infos = students.findByIds(ids);
            Map<UUID, String> names = identity ? students.displayNames(ids) : Map.of();
            for (StudentRecords.NearLimitRow r : rows) {
                StudentInfo s = infos.get(r.studentId());
                if (s == null || (programs != null && !programs.contains(s.programId()))
                        || (f.semester() != null && f.semester() != s.currentSemester())) {
                    continue;
                }
                List<Object> values = row(s.anonCode(), subjectNames.computeIfAbsent(r.groupId(), this::subjectName),
                        r.countedAbsences(), r.maxAbsences(), r.level().name());
                if (identity) {
                    values.add(1, names.get(r.studentId()));
                }
                sink.accept(values);
            }
        }));
    }

    private void forEachNearLimit(AnalyticsFilter f, Consumer<List<StudentRecords.NearLimitRow>> batchSink) {
        UUID after = null;
        while (true) {
            List<StudentRecords.NearLimitRow> batch = records.nearLimit(f.termId(), after, BATCH);
            if (batch.isEmpty()) {
                return;
            }
            batchSink.accept(batch);
            after = batch.get(batch.size() - 1).enrollmentId();
            if (batch.size() < BATCH) {
                return;
            }
        }
    }

    private String subjectName(UUID groupId) {
        return academic.findGroup(groupId).map(GroupInfo::subjectId).flatMap(academic::findSubject)
                .map(s -> s.name()).orElse(null);
    }

    private Map<UUID, String> programCodes() {
        Map<UUID, String> codes = new HashMap<>();
        academic.listPrograms().forEach((ProgramInfo p) -> codes.put(p.id(), p.code()));
        return codes;
    }

    private static List<Object> row(Object... values) {
        List<Object> row = new ArrayList<>(values.length + 1);
        for (Object value : values) {
            row.add(value);
        }
        return row;
    }
}
