package co.edu.retencion.ai.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Valida la salida de la IA contra los JSON Schema versionados de {@code ai/schemas/}. El esquema
 * estricto (con longitudes y rangos) se valida localmente; al proveedor se envía una versión sin las
 * palabras clave que la salida estructurada no admite ({@link #apiSchema(String)}).
 */
@Component
public class AiOutputValidator {

    /** Palabras clave que no se envían en {@code output_config.format}; se validan aquí. */
    private static final Set<String> LOCAL_ONLY_KEYWORDS = Set.of("$schema", "title", "minLength", "maxLength",
            "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "maxItems", "pattern", "format");

    private final ObjectMapper mapper;
    private final Map<String, JsonNode> raw = new ConcurrentHashMap<>();
    private final Map<String, JsonSchema> compiled = new ConcurrentHashMap<>();

    public AiOutputValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Errores de validación (vacío = válido). Los mensajes describen rutas, no contenido. */
    public List<String> validate(String schemaName, JsonNode output) {
        JsonSchema schema = compiled.computeIfAbsent(schemaName, name ->
                JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(load(name)));
        Set<ValidationMessage> errors = schema.validate(output);
        return errors.stream().map(e -> e.getInstanceLocation() + " " + e.getType()).sorted().toList();
    }

    /** Esquema para la salida estructurada del proveedor (sin restricciones solo locales). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> apiSchema(String schemaName) {
        JsonNode copy = load(schemaName).deepCopy();
        strip(copy, false);
        return mapper.convertValue(copy, Map.class);
    }

    public JsonNode load(String schemaName) {
        return raw.computeIfAbsent(schemaName, name -> {
            try (InputStream in = new ClassPathResource("ai/schemas/" + name + ".json").getInputStream()) {
                return mapper.readTree(in);
            } catch (IOException ex) {
                throw new UncheckedIOException("Esquema de IA inexistente: " + name, ex);
            }
        });
    }

    /**
     * @param propertyMap el nodo es el mapa {@code properties}: sus claves son nombres de campos
     *                    (p. ej. un campo llamado "title"), no palabras clave
     */
    private static void strip(JsonNode node, boolean propertyMap) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            if (!propertyMap) {
                object.remove(LOCAL_ONLY_KEYWORDS);
                JsonNode minItems = object.get("minItems");
                if (minItems != null && minItems.asInt() > 1) {
                    object.remove("minItems");
                }
            }
            Iterator<Map.Entry<String, JsonNode>> children = object.fields();
            while (children.hasNext()) {
                Map.Entry<String, JsonNode> child = children.next();
                strip(child.getValue(), !propertyMap && "properties".equals(child.getKey()));
            }
        } else if (node.isArray()) {
            node.forEach(n -> strip(n, false));
        }
    }
}
