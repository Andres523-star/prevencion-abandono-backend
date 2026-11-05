package co.edu.retencion.analytics.api;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import co.edu.retencion.analytics.AnalyticsFilter;
import co.edu.retencion.analytics.AnalyticsQueries;
import co.edu.retencion.analytics.AnalyticsViewer;
import co.edu.retencion.analytics.AnalyticsViews.AttendanceRisk;
import co.edu.retencion.analytics.AnalyticsViews.ProgramRow;
import co.edu.retencion.analytics.AnalyticsViews.Result;
import co.edu.retencion.analytics.AnalyticsViews.RetentionFunnel;
import co.edu.retencion.analytics.AnalyticsViews.RiskRow;
import co.edu.retencion.analytics.AnalyticsViews.SemesterRow;
import co.edu.retencion.analytics.AnalyticsViews.TrendPoint;
import co.edu.retencion.analytics.application.AnalyticsRefresher;
import co.edu.retencion.audit.Audited;
import co.edu.retencion.common.security.CurrentUser;

/** Gráficos. Todos reciben los mismos filtros; solo devuelven agregados con supresión de celdas. */
@RestController
@RequestMapping("/api/v1/analytics")
@Validated
@PreAuthorize("hasAnyRole('ADMIN','CONSEJERO','PSICOLOGO','COORDINADOR')")
@Tag(name = "Analítica")
public class AnalyticsController {

    private final AnalyticsQueries analytics;
    private final AnalyticsRefresher refresher;

    public AnalyticsController(AnalyticsQueries analytics, AnalyticsRefresher refresher) {
        this.analytics = analytics;
        this.refresher = refresher;
    }

    private static AnalyticsViewer viewer() {
        return AnalyticsViewer.of(CurrentUser.require());
    }

    @GetMapping("/dropout-by-program")
    @Operation(summary = "Deserción por programa (barras)")
    public Result<ProgramRow> dropoutByProgram(@Valid @ModelAttribute AnalyticsFilter filter) {
        return analytics.dropoutByProgram(filter.validated(), viewer());
    }

    @GetMapping("/trend")
    @Operation(summary = "Tendencia histórica de deserción por mes o año (líneas y área)")
    public Result<TrendPoint> trend(@Valid @ModelAttribute AnalyticsFilter filter,
                                    @RequestParam(defaultValue = "month") String granularity) {
        return analytics.trend(filter.validated(), granularity(granularity), viewer());
    }

    @GetMapping("/by-semester")
    @Operation(summary = "Estudiantes, retiros y en riesgo por semestre (tortas)")
    public Result<SemesterRow> bySemester(@Valid @ModelAttribute AnalyticsFilter filter) {
        return analytics.bySemester(filter.validated(), viewer());
    }

    @GetMapping("/risk-distribution")
    @Operation(summary = "Estudiantes activos por nivel de riesgo")
    public Result<RiskRow> riskDistribution(@Valid @ModelAttribute AnalyticsFilter filter) {
        return analytics.riskDistribution(filter.validated(), viewer());
    }

    @GetMapping("/attendance-risk")
    @Operation(summary = "Materias con más faltas e inscripciones cerca del límite")
    public AttendanceRisk attendanceRisk(@Valid @ModelAttribute AnalyticsFilter filter,
                                         @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return analytics.attendanceRisk(filter.validated(), limit, viewer());
    }

    @GetMapping("/retention-funnel")
    @Operation(summary = "Embudo de retención")
    public RetentionFunnel retentionFunnel(@Valid @ModelAttribute AnalyticsFilter filter) {
        return analytics.retentionFunnel(filter.validated(), viewer());
    }

    @PostMapping("/refresh")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "ANALYTICS_REFRESH", entity = "Analytics")
    @Operation(summary = "Refresca ahora las vistas de analítica")
    public Map<String, Boolean> refresh() {
        return Map.of("refreshed", refresher.refresh());
    }

    private static AnalyticsQueries.Granularity granularity(String value) {
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "month" -> AnalyticsQueries.Granularity.MONTH;
            case "year" -> AnalyticsQueries.Granularity.YEAR;
            default -> throw co.edu.retencion.common.error.DomainException.badRequest("granularity debe ser month o year");
        };
    }
}
