package co.edu.retencion.risk.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import co.edu.retencion.risk.ScoreType;

/**
 * Scorer con el modelo predictivo propio (gradient boosting calibrado, exportado a ONNX). El
 * resultado es una probabilidad calibrada ({@code scoreType = ML_CALIBRATED}); los factores
 * explicativos siguen viniendo de las reglas, para que toda salida muestre por qué.
 */
public final class MlScorer implements RiskScorer {

    /** Modelo de probabilidad (lo implementa ONNX Runtime en infraestructura). */
    public interface ProbabilityModel {

        String version();

        List<String> features();

        /** Probabilidad de deserción 0-1 para un vector en el orden de {@link #features()}. */
        double probability(float[] vector);
    }

    private final ProbabilityModel model;
    private final LevelThresholds thresholds;
    private final RulesScorer explanations;

    /**
     * @param model        modelo activo
     * @param thresholds   umbrales de probabilidad ({@code risk.ml.levels})
     * @param explanations scorer de reglas usado solo para los factores
     */
    public MlScorer(ProbabilityModel model, LevelThresholds thresholds, RulesScorer explanations) {
        this.model = model;
        this.thresholds = thresholds;
        this.explanations = explanations;
    }

    @Override
    public ScoreResult score(RiskFeatures features) {
        float[] vector = MlFeatureVector.vector(model.features(),
                MlFeatureVector.values(features.semester(), features.records()));
        double p = Math.max(0, Math.min(1, model.probability(vector)));
        BigDecimal probability = BigDecimal.valueOf(p).setScale(4, RoundingMode.HALF_UP);
        ScoreResult rules = explanations.score(features);
        return new ScoreResult(probability.movePointRight(2).setScale(2, RoundingMode.HALF_UP), ScoreType.ML_CALIBRATED,
                thresholds.levelFor(probability), probability, rules.factors(), model.version(), rules.version());
    }
}
