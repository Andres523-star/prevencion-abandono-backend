package co.edu.retencion.ai.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Prompts versionados ({@code ai/prompts/<nombre>.<versión>.txt}). La versión se guarda con cada
 * análisis: si cambia el texto, se crea una versión nueva y el historial sigue siendo comparable.
 */
@Component
public class PromptCatalog {

    public static final String RISK_EXPLAINER = "risk-explainer.v1";
    public static final String SUPPORT_PLAN = "support-plan.v1";
    public static final String RETENTION_AGENT = "retention-agent.v1";

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String text(String version) {
        return cache.computeIfAbsent(version, v -> {
            try (InputStream in = new ClassPathResource("ai/prompts/" + v + ".txt").getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            } catch (IOException ex) {
                throw new UncheckedIOException("Prompt inexistente: " + v, ex);
            }
        });
    }
}
