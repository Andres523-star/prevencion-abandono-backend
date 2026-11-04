package co.edu.retencion.risk.domain;

import java.math.BigDecimal;
import java.util.List;

import co.edu.retencion.risk.RiskLevel;
import co.edu.retencion.risk.ScoreType;

/**
 * Primera capa del diseño híbrido: calcula el puntaje de riesgo. Implementaciones:
 * {@link RulesScorer} (hoy) y {@link MlScorer} (modelo propio en ONNX). El orquestador no sabe cuál
 * usa: lo decide la fábrica según la configuración.
 */
public interface RiskScorer {

    ScoreResult score(RiskFeatures features);

    /**
     * @param score       0-100 (en ML: probabilidad × 100)
     * @param scoreType   RULES (puntaje) o ML_CALIBRATED (probabilidad)
     * @param level       nivel según los umbrales del tipo de puntaje
     * @param probability probabilidad 0-1, solo con ML_CALIBRATED
     * @param factors     factores (siempre de las reglas: toda salida muestra sus factores)
     * @param version     versión de las reglas (hash de su configuración) o del modelo ML
     * @param rulesVersion versión de las reglas usadas para los factores
     */
    record ScoreResult(BigDecimal score, ScoreType scoreType, RiskLevel level, BigDecimal probability,
                       List<Factor> factors, String version, String rulesVersion) {

        public ScoreResult {
            factors = List.copyOf(factors);
        }
    }

    /**
     * Umbrales de nivel.
     *
     * @param medium desde este valor el nivel es MEDIO
     * @param high   desde este valor el nivel es ALTO
     */
    record LevelThresholds(BigDecimal medium, BigDecimal high) {

        public RiskLevel levelFor(BigDecimal value) {
            if (value.compareTo(high) >= 0) {
                return RiskLevel.ALTO;
            }
            return value.compareTo(medium) >= 0 ? RiskLevel.MEDIO : RiskLevel.BAJO;
        }
    }
}
