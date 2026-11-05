package co.edu.retencion.analytics;

import co.edu.retencion.analytics.AnalyticsViews.AttendanceRisk;
import co.edu.retencion.analytics.AnalyticsViews.Overview;
import co.edu.retencion.analytics.AnalyticsViews.ProgramRow;
import co.edu.retencion.analytics.AnalyticsViews.Result;
import co.edu.retencion.analytics.AnalyticsViews.RetentionFunnel;
import co.edu.retencion.analytics.AnalyticsViews.RiskRow;
import co.edu.retencion.analytics.AnalyticsViews.SemesterRow;
import co.edu.retencion.analytics.AnalyticsViews.TrendPoint;

/**
 * API pública de analítica (gráficos, tablero y reportes). El alcance va explícito porque los reportes
 * se generan fuera de una petición.
 */
public interface AnalyticsQueries {

    /** Granularidad de la tendencia. */
    enum Granularity {
        MONTH,
        YEAR
    }

    Result<ProgramRow> dropoutByProgram(AnalyticsFilter filter, AnalyticsViewer viewer);

    Result<TrendPoint> trend(AnalyticsFilter filter, Granularity granularity, AnalyticsViewer viewer);

    Result<SemesterRow> bySemester(AnalyticsFilter filter, AnalyticsViewer viewer);

    Result<RiskRow> riskDistribution(AnalyticsFilter filter, AnalyticsViewer viewer);

    AttendanceRisk attendanceRisk(AnalyticsFilter filter, int limit, AnalyticsViewer viewer);

    RetentionFunnel retentionFunnel(AnalyticsFilter filter, AnalyticsViewer viewer);

    Overview overview(AnalyticsViewer viewer);
}
