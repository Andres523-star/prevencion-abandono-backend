package co.edu.retencion.settings;

import java.time.Duration;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Lectura de parámetros configurables. Los demás módulos dependen solo de esta interfaz.
 * Los métodos tipados devuelven el valor por defecto si la clave no existe o el tipo no coincide.
 */
public interface SettingsProvider {

    Optional<JsonNode> value(String key);

    default int getInt(String key, int defaultValue) {
        return value(key).filter(JsonNode::isNumber).map(JsonNode::asInt).orElse(defaultValue);
    }

    default long getLong(String key, long defaultValue) {
        return value(key).filter(JsonNode::isNumber).map(JsonNode::asLong).orElse(defaultValue);
    }

    default boolean getBoolean(String key, boolean defaultValue) {
        return value(key).filter(JsonNode::isBoolean).map(JsonNode::asBoolean).orElse(defaultValue);
    }

    default String getString(String key, String defaultValue) {
        return value(key).filter(JsonNode::isTextual).map(JsonNode::asText).orElse(defaultValue);
    }

    /** Duración expresada en segundos en la configuración. */
    default Duration getSeconds(String key, Duration defaultValue) {
        return value(key).filter(JsonNode::isNumber).map(n -> Duration.ofSeconds(n.asLong())).orElse(defaultValue);
    }
}
