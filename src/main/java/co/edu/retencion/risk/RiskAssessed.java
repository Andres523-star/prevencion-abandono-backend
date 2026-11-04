package co.edu.retencion.risk;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.modulith.events.Externalized;

/**
 * Evento: se guardó un análisis de riesgo. Lo consumirán las alertas y la campanita (Fase 5). Sin
 * datos personales: solo ids, nivel, puntaje y códigos de factores.
 *
 * @param assessmentId  análisis
 * @param studentId     estudiante
 * @param level         nivel nuevo
 * @param previousLevel nivel anterior ({@code null} si es el primero)
 * @param score         puntaje
 * @param scoreType     tipo de puntaje
 * @param source        fuente
 * @param trigger       origen
 * @param factorCodes   códigos de los factores detectados
 */
@Externalized("retencion.events::risk.assessed")
public record RiskAssessed(UUID assessmentId, UUID studentId, RiskLevel level, RiskLevel previousLevel,
                           BigDecimal score, ScoreType scoreType, AssessmentSource source, RiskTrigger trigger,
                           List<String> factorCodes) {

    /** Subió de nivel o es el primer análisis no BAJO. */
    public boolean escalated() {
        return previousLevel == null ? level != RiskLevel.BAJO : level.isAbove(previousLevel);
    }
}
