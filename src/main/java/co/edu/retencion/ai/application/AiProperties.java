package co.edu.retencion.ai.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;

import co.edu.retencion.ai.ModelTier;

/**
 * Configuración de la integración con Claude (prefijo {@code ai}). La clave solo llega por variable
 * de entorno ({@code ANTHROPIC_API_KEY}); sin clave el módulo responde siempre con el motor de reglas.
 *
 * @param apiKey               clave de la API de Anthropic
 * @param baseUrl              URL base (en pruebas apunta a WireMock)
 * @param model                modelos por nivel ({@code ai.model.default}, {@code ai.model.triage})
 * @param effort               esfuerzo por nivel (low, medium, high, xhigh, max)
 * @param maxTokens            tope de tokens de salida por respuesta
 * @param timeout              tiempo máximo por petición HTTP
 * @param maxRetries           reintentos del SDK ante 408/409/429/5xx y errores de red
 * @param invalidOutputRetries reintentos propios cuando el JSON no cumple el esquema
 * @param refusalFallback      pedir al servidor que reintente con otro modelo si el modelo capaz declina
 * @param agentMaxIterations   vueltas máximas del agente de retención
 */
@ConfigurationProperties(prefix = "ai")
public record AiProperties(String apiKey, @DefaultValue("https://api.anthropic.com") String baseUrl,
                           @DefaultValue Models model, @DefaultValue Efforts effort,
                           @DefaultValue("16000") long maxTokens, @DefaultValue("60s") Duration timeout,
                           @DefaultValue("2") int maxRetries, @DefaultValue("1") int invalidOutputRetries,
                           @DefaultValue("true") boolean refusalFallback,
                           @DefaultValue("6") int agentMaxIterations) {

    /**
     * @param defaultModel modelo capaz (casos críticos y retención)
     * @param triage       modelo económico (análisis masivo)
     */
    public record Models(@Name("default") @DefaultValue("claude-opus-5-5") String defaultModel,
                         @DefaultValue("claude-haiku-5-5") String triage) {
    }

    public record Efforts(@Name("default") @DefaultValue("medium") String defaultEffort,
                          @DefaultValue("low") String triage) {
    }

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String modelFor(ModelTier tier) {
        return tier == ModelTier.DEFAULT ? model.defaultModel() : model.triage();
    }

    public String effortFor(ModelTier tier) {
        return tier == ModelTier.DEFAULT ? effort.defaultEffort() : effort.triage();
    }

    @Override
    public String toString() {
        return "AiProperties[baseUrl=" + baseUrl + ", model=" + model + ", configured=" + configured() + "]";
    }
}
