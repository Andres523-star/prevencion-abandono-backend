package co.edu.retencion.privacy.application;

import java.io.IOException;
import java.io.InputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Registro de actividades de tratamiento (qué datos, para qué, con qué base legal, a quién se transmiten y
 * cuánto se conservan). Vive en {@code privacy/registro-actividades.json} para que el área jurídica lo revise
 * junto con el código y cualquier cambio quede en el historial del repositorio.
 */
@Component
public class ProcessingActivities {

    private final JsonNode registry;

    public ProcessingActivities(ObjectMapper mapper) throws IOException {
        try (InputStream in = new ClassPathResource("privacy/registro-actividades.json").getInputStream()) {
            this.registry = mapper.readTree(in);
        }
    }

    public JsonNode registry() {
        return registry;
    }
}
