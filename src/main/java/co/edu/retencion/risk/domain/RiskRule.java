package co.edu.retencion.risk.domain;

import java.util.List;

/**
 * Regla del motor de riesgo (<b>patrón Strategy</b>): cada regla evalúa un aspecto, devuelve sus
 * factores con aporte y explicación, y se configura con su propio objeto JSON en settings.
 */
public interface RiskRule {

    /** Código del factor que produce. */
    String code();

    /** Clave de {@code app_settings} con sus parámetros. */
    String settingKey();

    /** Factores detectados (vacío si la regla no aplica o está desactivada). */
    List<Factor> evaluate(RiskFeatures features, RuleSettings settings);
}
