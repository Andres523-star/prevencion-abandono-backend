package co.edu.retencion.risk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Consultas públicas del motor de riesgo para alertas, campanita, chat e intervenciones. Entrega
 * los factores con su categoría: cada consumidor aplica el mínimo necesario de su destinatario.
 */
public interface RiskQueries {

    Optional<LatestRisk> latest(UUID studentId);

    /** Historial (más reciente primero), para las gráficas de evolución. */
    List<LatestRisk> history(UUID studentId, int limit);

    /** Detalle de un análisis: factores y acciones sugeridas. */
    Optional<AssessmentSummary> summary(UUID assessmentId);

    /** Último nivel de cada estudiante, paginado por keyset sobre el id del estudiante. */
    List<RiskRow> page(RiskListQuery query);

    /** Cuántos de esos estudiantes hay en cada nivel. */
    java.util.Map<RiskLevel, Long> levelCounts(java.util.Collection<UUID> studentIds);

    /**
     * @param assessmentId análisis
     * @param level        nivel
     * @param score        puntaje
     * @param scoreType    tipo de puntaje
     * @param assessedAt   fecha
     */
    /**
     * @param programIds {@code null} = todos
     * @param studentIds {@code null} = todos
     * @param after      cursor (id del último estudiante de la página anterior)
     */
    record RiskListQuery(java.util.Set<RiskLevel> levels, java.util.Set<UUID> programIds, java.util.Set<UUID> studentIds,
                         UUID after, int limit) {
    }

    /** Fila de un listado por nivel (sin factores: el motivo se consulta aparte, según el rol). */
    record RiskRow(UUID studentId, UUID programId, Integer semester, RiskLevel level, BigDecimal score,
                   Instant assessedAt) {
    }

    record LatestRisk(UUID assessmentId, RiskLevel level, BigDecimal score, ScoreType scoreType, Instant assessedAt) {
    }

    /**
     * @param sensitiveContext la IA recibió señales sensibles (su texto solo lo ve PSICOLOGO)
     * @param factors          factores del motor
     * @param suggestedActions recomendaciones de la IA (vacío si no la hubo)
     */
    record AssessmentSummary(UUID assessmentId, UUID studentId, RiskLevel level, BigDecimal score,
                             ScoreType scoreType, boolean sensitiveContext, List<FactorSummary> factors,
                             List<SuggestedAction> suggestedActions, Instant assessedAt) {
    }

    /**
     * @param category ACADEMICO, ASISTENCIA, CONDUCTA, BIENESTAR, SOCIOECONOMICO o CAUSAS
     * @param details  motivo estructurado (materia, promedio, faltas...)
     */
    record FactorSummary(String code, String category, BigDecimal points, String explanation,
                         Map<String, Object> details) {
    }

    record SuggestedAction(String resourceId, String action, String priority, String responsibleRole) {
    }
}
