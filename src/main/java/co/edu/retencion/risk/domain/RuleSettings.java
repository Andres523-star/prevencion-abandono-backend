package co.edu.retencion.risk.domain;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

/**
 * Parámetros de una regla, leídos del objeto JSON de {@code app_settings} ({@code risk.rules.*}).
 * Un valor ausente o de tipo incorrecto toma el valor por defecto: un error de digitación no apaga
 * el motor.
 *
 * @param node objeto JSON de la regla
 */
public record RuleSettings(JsonNode node) {

    public static RuleSettings of(JsonNode node) {
        return new RuleSettings(node == null ? MissingNode.getInstance() : node);
    }

    public boolean enabled() {
        JsonNode v = node.path("enabled");
        return !v.isBoolean() || v.asBoolean();
    }

    public BigDecimal decimal(String field, String defaultValue) {
        JsonNode v = node.path(field);
        return v.isNumber() ? v.decimalValue() : new BigDecimal(defaultValue);
    }

    public int integer(String field, int defaultValue) {
        JsonNode v = node.path(field);
        return v.isNumber() ? Math.max(0, v.asInt()) : defaultValue;
    }

    /** Puntos de un mapa {@code {"ACADEMICA": 5, ...}}. */
    public int pointsFor(String mapField, String key, int defaultValue) {
        JsonNode v = node.path(mapField).path(key);
        return v.isNumber() ? Math.max(0, v.asInt()) : defaultValue;
    }
}
