package co.edu.retencion.risk.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

import co.edu.retencion.records.AttendanceLevel;
import co.edu.retencion.risk.RiskLevel;
import co.edu.retencion.risk.domain.RiskRule;
import co.edu.retencion.risk.domain.RiskRules;
import co.edu.retencion.risk.domain.RiskScorer.LevelThresholds;
import co.edu.retencion.settings.SettingsProvider;

/**
 * Lee los parámetros del motor desde {@code app_settings} en cada análisis (con la caché de 30 s
 * del proxy de settings, que se invalida al cambiar un valor): cambiar un umbral cambia el
 * resultado sin redeploy.
 */
@Component
public class RiskSettings {

    private final SettingsProvider settings;

    public RiskSettings(SettingsProvider settings) {
        this.settings = settings;
    }

    /**
     * Parámetros vigentes para un análisis.
     *
     * @param rules            objeto JSON de cada regla por clave
     * @param levels           umbrales del puntaje de reglas
     * @param mlLevels         umbrales de probabilidad del modelo ML
     * @param activeMlVersion  versión del modelo ML activo (vacío = reglas)
     * @param watchDays        días de vigilancia
     * @param aiCanEscalate    la IA puede subir el nivel
     * @param debounce         espera antes de reanalizar por eventos
     * @param aiMinLevel       nivel mínimo para pedir IA en eventos y lote
     * @param planAiMinLevel   nivel de asistencia desde el que el plan lo redacta la IA
     * @param freeText         se envía texto libre redactado a la IA
     */
    public record Snapshot(Map<String, JsonNode> rules, LevelThresholds levels, LevelThresholds mlLevels,
                           String activeMlVersion, int watchDays, boolean aiCanEscalate, Duration debounce,
                           RiskLevel aiMinLevel, AttendanceLevel planAiMinLevel, boolean freeText) {
    }

    public Snapshot snapshot() {
        Map<String, JsonNode> rules = new HashMap<>();
        for (RiskRule rule : RiskRules.all()) {
            settings.value(rule.settingKey()).filter(JsonNode::isObject).ifPresent(v -> rules.put(rule.settingKey(), v));
        }
        return new Snapshot(rules,
                thresholds("risk.levels", "30", "60"),
                thresholds("risk.ml.levels", "0.30", "0.60"),
                settings.getString("risk.ml.active-version", "").trim(),
                Math.max(1, settings.getInt("risk.watch-days", 30)),
                settings.getBoolean("risk.ai-can-escalate", false),
                Duration.ofSeconds(Math.max(0, settings.getLong("risk.event-debounce-seconds", 120))),
                parse(RiskLevel.class, settings.getString("ai.min-level", "MEDIO"), RiskLevel.MEDIO),
                parse(AttendanceLevel.class, settings.getString("ai.support-plan.min-level", "CRITICO"),
                        AttendanceLevel.CRITICO),
                settings.getBoolean("ai.free-text.enabled", false));
    }

    private LevelThresholds thresholds(String key, String medium, String high) {
        JsonNode node = settings.value(key).orElse(null);
        BigDecimal m = node != null && node.path("medium").isNumber() ? node.path("medium").decimalValue()
                : new BigDecimal(medium);
        BigDecimal h = node != null && node.path("high").isNumber() ? node.path("high").decimalValue()
                : new BigDecimal(high);
        if (h.compareTo(m) <= 0) {
            // Configuración incoherente: se usan los valores por defecto antes que analizar mal.
            return new LevelThresholds(new BigDecimal(medium), new BigDecimal(high));
        }
        return new LevelThresholds(m, h);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
