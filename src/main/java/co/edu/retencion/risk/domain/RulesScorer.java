package co.edu.retencion.risk.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;

import co.edu.retencion.risk.ScoreType;

/**
 * Scorer por reglas: suma los aportes de cada {@link RiskRule} (tope 100) y asigna el nivel con los
 * umbrales de {@code risk.levels}. El resultado es un PUNTAJE, no una probabilidad calibrada
 * ({@code scoreType = RULES}).
 *
 * <p>La versión es el hash de la configuración de todas las reglas: cada análisis guarda con qué
 * parámetros se calculó, y un cambio en settings produce una versión nueva sin redeploy.
 */
public final class RulesScorer implements RiskScorer {

    static final BigDecimal MAX_SCORE = BigDecimal.valueOf(100);

    private final List<RiskRule> rules;
    private final Map<String, JsonNode> configuration;
    private final LevelThresholds thresholds;
    private final String version;

    /**
     * @param rules         reglas a aplicar
     * @param configuration objeto JSON de cada regla por clave de settings (puede faltar alguna)
     * @param thresholds    umbrales de nivel
     */
    public RulesScorer(List<RiskRule> rules, Map<String, JsonNode> configuration, LevelThresholds thresholds) {
        this.rules = List.copyOf(rules);
        this.configuration = new TreeMap<>(configuration);
        this.thresholds = thresholds;
        this.version = versionOf(this.configuration, thresholds);
    }

    @Override
    public ScoreResult score(RiskFeatures features) {
        List<Factor> factors = new ArrayList<>();
        for (RiskRule rule : rules) {
            RuleSettings settings = RuleSettings.of(configuration.get(rule.settingKey()));
            if (settings.enabled()) {
                factors.addAll(rule.evaluate(features, settings));
            }
        }
        BigDecimal total = factors.stream().map(Factor::points).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal score = total.min(MAX_SCORE).setScale(2, java.math.RoundingMode.HALF_UP);
        return new ScoreResult(score, ScoreType.RULES, thresholds.levelFor(score), null, factors, version, version);
    }

    public String version() {
        return version;
    }

    static String versionOf(Map<String, JsonNode> configuration, LevelThresholds thresholds) {
        StringBuilder canonical = new StringBuilder();
        configuration.forEach((k, v) -> canonical.append(k).append('=').append(v).append(';'));
        canonical.append("levels=").append(thresholds.medium()).append('/').append(thresholds.high());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return "rules-" + HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
