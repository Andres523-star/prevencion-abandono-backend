package co.edu.retencion.ai;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SPI de solo lectura para el agente de retención. La implementa el módulo risk (que conoce los
 * datos) y la consume este módulo (que habla con la IA), sin ciclo de dependencias. Todo lo que
 * devuelve está seudonimizado y vuelve a pasar por la lista blanca antes de salir.
 */
public interface RetentionAgentTools {

    /** Hechos del caso y datos de elegibilidad, o vacío si el estudiante no existe. */
    Optional<AgentCase> caseOf(UUID studentId);

    /** Historial de riesgo más reciente primero. */
    List<HistoryEntry> history(UUID studentId, int limit);

    /**
     * @param facts       hechos seudonimizados
     * @param eligibility datos para filtrar recursos (no salen hacia la IA)
     */
    record AgentCase(CaseFacts facts, ResourceCatalog.EligibilityProfile eligibility) {
    }

    /**
     * @param assessedAt  fecha del análisis
     * @param level       nivel
     * @param score       puntaje
     * @param scoreType   RULES o ML_CALIBRATED
     * @param source      RULES, AI o HYBRID
     * @param factorCodes códigos de los factores
     */
    record HistoryEntry(Instant assessedAt, String level, BigDecimal score, String scoreType, String source,
                        List<String> factorCodes) {
    }
}
