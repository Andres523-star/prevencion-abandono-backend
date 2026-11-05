package co.edu.retencion.analytics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Respuestas de analítica. Un conteo {@code null} con {@code suppressed = true} es una celda suprimida:
 * tenía menos estudiantes que el tamaño mínimo (o se ocultó para que no se deduzca de un total).
 */
public final class AnalyticsViews {

    private AnalyticsViews() {
    }

    /**
     * @param minCellSize tamaño mínimo de celda aplicado
     * @param refreshedAt último refresco de las vistas ({@code null} si nunca se han refrescado)
     */
    public record Result<T>(int minCellSize, Instant refreshedAt, List<T> rows) {
    }

    public record ProgramRow(UUID programId, String programCode, String programName, Long students, Long dropouts,
                             BigDecimal dropoutRate, boolean suppressed) {
    }

    /**
     * @param period   {@code 2026-03} o {@code 2026}
     * @param enrolled solo se conoce por año o en la serie histórica
     * @param source   {@code LIVE}, {@code HISTORICAL} o {@code MIXED}
     */
    public record TrendPoint(String period, Long dropouts, Long enrolled, BigDecimal dropoutRate, String source,
                             boolean suppressed) {
    }

    public record SemesterRow(int semester, Long students, Long dropouts, Long atRisk, boolean suppressed) {
    }

    public record RiskRow(String level, Long students, BigDecimal share, boolean suppressed) {
    }

    public record SubjectRow(UUID subjectId, String subjectCode, String subjectName, Long enrollments, Long absences,
                             Long warning, Long critical, Long lost, boolean suppressed) {
    }

    /** Inscripciones cerca del límite, por nivel de la política de asistencia. */
    public record NearLimit(Long warning, Long critical, Long lost) {
    }

    public record AttendanceRisk(int minCellSize, Instant refreshedAt, List<SubjectRow> subjects, NearLimit nearLimit) {
    }

    public record RetentionFunnel(int minCellSize, Instant refreshedAt, Long detected, Long contacted, Long inProgress,
                                  Long retained, Long postponed, Long transferred, Long withdrawn) {
    }

    /** Totales del tablero. Sin supresión: es para personal con acceso al detalle de esos estudiantes. */
    public record Overview(long students, long active, long high, long medium, long low, long withdrawn,
                           BigDecimal dropoutRate, Instant refreshedAt) {
    }
}
