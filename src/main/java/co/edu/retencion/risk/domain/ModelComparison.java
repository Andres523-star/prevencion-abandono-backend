package co.edu.retencion.risk.domain;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Compara las métricas de un modelo candidato con las del modelo activo antes de aprobarlo
 * (servicio de dominio puro). Métricas esperadas en la ficha del modelo: {@code auc},
 * {@code recallHighRisk}, {@code brier}, {@code ece} (error de calibración), {@code byProgram} y
 * {@code byGroup} (desempeño por grupo, p. ej. bandas de estrato, para la revisión de sesgo).
 */
public final class ModelComparison {

    static final double MAX_AUC_DROP = 0.01;
    static final double MAX_RECALL_DROP = 0.05;
    static final double MAX_ECE_INCREASE = 0.02;
    static final double MAX_GROUP_AUC_GAP = 0.10;
    static final double MIN_AUC = 0.60;

    private ModelComparison() {
    }

    /** Veredicto de la comparación. */
    public enum Verdict {
        MEJOR,
        EQUIVALENTE,
        PEOR,
        /** No hay modelo activo: se evalúa solo contra los mínimos absolutos. */
        SIN_REFERENCIA
    }

    /**
     * @param verdict  veredicto
     * @param deltas   diferencias candidato − actual por métrica
     * @param blockers motivos que impiden aprobar sin anulación explícita
     * @param warnings advertencias (no bloquean)
     */
    public record Result(Verdict verdict, Map<String, Double> deltas, List<String> blockers, List<String> warnings) {

        public boolean approvable() {
            return blockers.isEmpty();
        }
    }

    public static Result compare(JsonNode candidate, JsonNode current) {
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Double> deltas = new LinkedHashMap<>();
        double auc = candidate.path("auc").asDouble(Double.NaN);
        if (Double.isNaN(auc)) {
            blockers.add("La ficha no trae AUC");
        } else if (auc < MIN_AUC) {
            blockers.add("AUC " + auc + " menor al mínimo " + MIN_AUC);
        }
        double gap = groupGap(candidate.path("byGroup"));
        if (gap > MAX_GROUP_AUC_GAP) {
            blockers.add("Brecha de AUC entre grupos de " + round(gap) + " (máximo " + MAX_GROUP_AUC_GAP + ")");
        }
        double programGap = groupGap(candidate.path("byProgram"));
        if (programGap > MAX_GROUP_AUC_GAP) {
            warnings.add("Brecha de AUC entre programas de " + round(programGap));
        }
        if (current == null || current.isMissingNode() || current.isNull()) {
            return new Result(Verdict.SIN_REFERENCIA, deltas, blockers, warnings);
        }
        for (String metric : List.of("auc", "recallHighRisk", "brier", "ece")) {
            if (candidate.path(metric).isNumber() && current.path(metric).isNumber()) {
                deltas.put(metric, round(candidate.path(metric).asDouble() - current.path(metric).asDouble()));
            }
        }
        boolean worse = false;
        if (deltas.getOrDefault("auc", 0.0) < -MAX_AUC_DROP) {
            blockers.add("El AUC baja " + -deltas.get("auc"));
            worse = true;
        }
        if (deltas.getOrDefault("recallHighRisk", 0.0) < -MAX_RECALL_DROP) {
            blockers.add("El recall en riesgo alto baja " + -deltas.get("recallHighRisk"));
            worse = true;
        }
        if (deltas.getOrDefault("ece", 0.0) > MAX_ECE_INCREASE) {
            blockers.add("La calibración empeora (ECE +" + deltas.get("ece") + ")");
            worse = true;
        }
        Verdict verdict = worse ? Verdict.PEOR
                : deltas.getOrDefault("auc", 0.0) > 0.005 ? Verdict.MEJOR : Verdict.EQUIVALENTE;
        return new Result(verdict, deltas, blockers, warnings);
    }

    /** Diferencia entre el mejor y el peor AUC de los subgrupos ({@code {"grupo": {"auc": x}}}). */
    static double groupGap(JsonNode groups) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        Iterator<JsonNode> it = groups.elements();
        int n = 0;
        while (it.hasNext()) {
            JsonNode g = it.next();
            if (g.path("auc").isNumber()) {
                min = Math.min(min, g.path("auc").asDouble());
                max = Math.max(max, g.path("auc").asDouble());
                n++;
            }
        }
        return n < 2 ? 0 : max - min;
    }

    private static double round(double v) {
        return Math.round(v * 10_000d) / 10_000d;
    }
}
