package co.edu.retencion.settings.application;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * DTO de lectura de un parámetro.
 *
 * @param key         clave
 * @param value       valor JSON
 * @param description para qué sirve
 * @param version     versión para edición optimista
 * @param updatedAt   última modificación
 */
public record SettingView(String key, JsonNode value, String description, long version, Instant updatedAt) {
}
