package co.edu.retencion.analytics.application;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;

import org.springframework.stereotype.Service;

import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.academic.ProgramInfo;
import co.edu.retencion.analytics.AnalyticsFilter;
import co.edu.retencion.analytics.AnalyticsQueries;
import co.edu.retencion.analytics.AnalyticsViewer;
import co.edu.retencion.analytics.AnalyticsViews.AttendanceRisk;
import co.edu.retencion.analytics.AnalyticsViews.NearLimit;
import co.edu.retencion.analytics.AnalyticsViews.Overview;
import co.edu.retencion.analytics.AnalyticsViews.ProgramRow;
import co.edu.retencion.analytics.AnalyticsViews.Result;
import co.edu.retencion.analytics.AnalyticsViews.RetentionFunnel;
import co.edu.retencion.analytics.AnalyticsViews.RiskRow;
import co.edu.retencion.analytics.AnalyticsViews.SemesterRow;
import co.edu.retencion.analytics.AnalyticsViews.SubjectRow;
import co.edu.retencion.analytics.AnalyticsViews.TrendPoint;
import co.edu.retencion.analytics.domain.CellSuppressor;
import co.edu.retencion.analytics.domain.TrendMerger;
import co.edu.retencion.analytics.infrastructure.AnalyticsRepository;
import co.edu.retencion.analytics.infrastructure.AnalyticsRepository.FunnelCount;
import co.edu.retencion.analytics.infrastructure.AnalyticsRepository.LevelTotals;
import co.edu.retencion.analytics.infrastructure.AnalyticsRepository.OverviewCount;
import co.edu.retencion.analytics.infrastructure.AnalyticsRepository.ProgramCount;
import co.edu.retencion.analytics.infrastructure.AnalyticsRepository.SemesterCount;
import co.edu.retencion.analytics.infrastructure.AnalyticsRepository.SubjectCount;
import co.edu.retencion.settings.SettingsProvider;

/**
 * Indicadores agregados. Consulta las vistas, aplica la supresión de celdas y cachea el resultado ya
 * suprimido: en la caché nunca hay conteos pequeños.
 */
@Service
public class AnalyticsService implements AnalyticsQueries {

    private static final List<String> LEVELS = List.of("ALTO", "MEDIO", "BAJO", "SIN_ANALISIS");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter YEAR = DateTimeFormatter.ofPattern("yyyy");

    private final AnalyticsRepository repository;
    private final AnalyticsCache cache;
    private final AcademicDirectory academic;
    private final SettingsProvider settings;

    public AnalyticsService(AnalyticsRepository repository, AnalyticsCache cache, AcademicDirectory academic,
                            SettingsProvider settings) {
        this.repository = repository;
        this.cache = cache;
        this.academic = academic;
        this.settings = settings;
    }

    /** Lo que distingue una respuesta en la caché. */
    private record Key(AnalyticsFilter filter, Object extra, List<UUID> programs, int minCell) {
    }

    private Key key(AnalyticsFilter filter, Object extra, AnalyticsViewer viewer, int minCell) {
        var programs = viewer.effective(filter.programId());
        return new Key(filter, extra, programs == null ? null : new ArrayList<>(new TreeSet<>(programs)), minCell);
    }

    private int minCell() {
        return Math.max(1, settings.getInt("analytics.min-cell-size", 10));
    }

    private Instant refreshedAt() {
        return repository.refreshedAt().orElse(null);
    }

    @Override
    public Result<ProgramRow> dropoutByProgram(AnalyticsFilter filter, AnalyticsViewer viewer) {
        int min = minCell();
        return cache.get("dropout-by-program", key(filter, null, viewer, min), new TypeReference<>() { }, () -> {
            Map<UUID, ProgramInfo> programs = academic.listPrograms().stream()
                    .collect(Collectors.toMap(ProgramInfo::id, Function.identity()));
            List<ProgramCount> counts = repository.dropoutByProgram(filter, viewer);
            List<Long> students = CellSuppressor.maskColumn(counts.stream().map(ProgramCount::students).toList(), min);
            List<Long> dropouts = CellSuppressor.maskColumn(counts.stream().map(ProgramCount::dropouts).toList(), min);
            List<ProgramRow> rows = new ArrayList<>();
            for (int i = 0; i < counts.size(); i++) {
                ProgramInfo p = programs.get(counts.get(i).programId());
                Long total = students.get(i);
                Long out = total == null ? null : dropouts.get(i);
                rows.add(new ProgramRow(counts.get(i).programId(), p == null ? null : p.code(), p == null ? null : p.name(),
                        total, out, CellSuppressor.percentage(out, total), total == null || out == null));
            }
            rows.sort(java.util.Comparator.comparing(ProgramRow::programCode,
                    java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
            return new Result<>(min, refreshedAt(), rows);
        });
    }

    @Override
    public Result<TrendPoint> trend(AnalyticsFilter filter, Granularity granularity, AnalyticsViewer viewer) {
        int min = minCell();
        return cache.get("trend", key(filter, granularity, viewer, min), new TypeReference<>() { }, () -> {
            DateTimeFormatter format = granularity == Granularity.YEAR ? YEAR : MONTH;
            List<TrendMerger.Merged> merged = TrendMerger.merge(repository.liveTrend(filter, granularity, viewer),
                    repository.historicalTrend(filter, granularity, viewer));
            List<TrendPoint> rows = new ArrayList<>();
            for (TrendMerger.Merged m : merged) {
                Long dropouts = CellSuppressor.mask(m.dropouts(), min);
                Long enrolled = m.enrolled() == null ? null : CellSuppressor.mask(m.enrolled(), min);
                boolean hidden = dropouts == null || (m.enrolled() != null && enrolled == null);
                rows.add(new TrendPoint(format.format(m.period()), hidden ? null : dropouts, enrolled,
                        hidden ? null : CellSuppressor.percentage(dropouts, enrolled), m.source(), hidden));
            }
            return new Result<>(min, refreshedAt(), rows);
        });
    }

    @Override
    public Result<SemesterRow> bySemester(AnalyticsFilter filter, AnalyticsViewer viewer) {
        int min = minCell();
        return cache.get("by-semester", key(filter, null, viewer, min), new TypeReference<>() { }, () -> {
            List<SemesterCount> counts = repository.bySemester(filter, viewer);
            List<Long> students = CellSuppressor.maskColumn(counts.stream().map(SemesterCount::students).toList(), min);
            List<Long> dropouts = CellSuppressor.maskColumn(counts.stream().map(SemesterCount::dropouts).toList(), min);
            List<Long> atRisk = CellSuppressor.maskColumn(counts.stream().map(SemesterCount::atRisk).toList(), min);
            List<SemesterRow> rows = new ArrayList<>();
            for (int i = 0; i < counts.size(); i++) {
                Long total = students.get(i);
                Long out = total == null ? null : dropouts.get(i);
                Long risk = total == null ? null : atRisk.get(i);
                rows.add(new SemesterRow(counts.get(i).semester(), total, out, risk,
                        total == null || out == null || risk == null));
            }
            return new Result<>(min, refreshedAt(), rows);
        });
    }

    @Override
    public Result<RiskRow> riskDistribution(AnalyticsFilter filter, AnalyticsViewer viewer) {
        int min = minCell();
        return cache.get("risk-distribution", key(filter, null, viewer, min), new TypeReference<>() { }, () -> {
            Map<String, Long> levels = repository.riskDistribution(filter, viewer);
            List<Long> raw = LEVELS.stream().map(l -> levels.getOrDefault(l, 0L)).toList();
            long total = raw.stream().mapToLong(Long::longValue).sum();
            List<Long> masked = CellSuppressor.maskColumn(raw, min);
            List<RiskRow> rows = new ArrayList<>();
            for (int i = 0; i < LEVELS.size(); i++) {
                rows.add(new RiskRow(LEVELS.get(i), masked.get(i), CellSuppressor.percentage(masked.get(i), total),
                        masked.get(i) == null));
            }
            return new Result<>(min, refreshedAt(), rows);
        });
    }

    @Override
    public AttendanceRisk attendanceRisk(AnalyticsFilter filter, int limit, AnalyticsViewer viewer) {
        int min = minCell();
        return cache.get("attendance-risk", key(filter, limit, viewer, min), new TypeReference<>() { }, () -> {
            List<SubjectRow> subjects = new ArrayList<>();
            for (SubjectCount s : repository.subjects(filter, limit, viewer)) {
                Long enrollments = CellSuppressor.mask(s.enrollments(), min);
                boolean small = enrollments == null;
                Long warning = small ? null : CellSuppressor.mask(s.warning(), min);
                Long critical = small ? null : CellSuppressor.mask(s.critical(), min);
                Long lost = small ? null : CellSuppressor.mask(s.lost(), min);
                subjects.add(new SubjectRow(s.subjectId(), s.code(), s.name(), enrollments, small ? null : s.absences(),
                        warning, critical, lost, small || warning == null || critical == null || lost == null));
            }
            LevelTotals totals = repository.levelTotals(filter, viewer);
            List<Long> near = CellSuppressor.maskColumn(List.of(totals.warning(), totals.critical(), totals.lost()), min);
            return new AttendanceRisk(min, refreshedAt(), subjects, new NearLimit(near.get(0), near.get(1), near.get(2)));
        });
    }

    @Override
    public RetentionFunnel retentionFunnel(AnalyticsFilter filter, AnalyticsViewer viewer) {
        int min = minCell();
        return cache.get("retention-funnel", key(filter, null, viewer, min), new TypeReference<>() { }, () -> {
            FunnelCount f = repository.funnel(filter, viewer);
            // En curso + resultados suman los detectados: sin complemento se deduciría la celda oculta.
            List<Long> parts = CellSuppressor.maskColumn(List.of(f.inProgress(), f.retained(), f.postponed(),
                    f.transferred(), f.withdrawn()), min);
            return new RetentionFunnel(min, refreshedAt(), CellSuppressor.mask(f.detected(), min),
                    CellSuppressor.mask(f.contacted(), min), parts.get(0), parts.get(1), parts.get(2), parts.get(3),
                    parts.get(4));
        });
    }

    @Override
    public Overview overview(AnalyticsViewer viewer) {
        return cache.get("overview", key(AnalyticsFilter.none(), null, viewer, 0), new TypeReference<>() { }, () -> {
            OverviewCount c = repository.overview(viewer);
            return new Overview(c.students(), c.active(), c.high(), c.medium(), c.low(), c.withdrawn(),
                    CellSuppressor.percentage(c.withdrawn(), c.students()), refreshedAt());
        });
    }
}
