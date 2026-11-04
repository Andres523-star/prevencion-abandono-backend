package co.edu.retencion.ai.application;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import co.edu.retencion.ai.AiCallInfo;
import co.edu.retencion.ai.AiFailure;
import co.edu.retencion.ai.Anonymizer.AnonymizedPayload;
import co.edu.retencion.ai.ModelTier;
import co.edu.retencion.ai.application.LlmGateway.LlmException;
import co.edu.retencion.ai.domain.PayloadWhitelist;
import co.edu.retencion.ai.domain.PiiRedactor;
import co.edu.retencion.settings.SettingsProvider;

/**
 * Llamada con salida estructurada: arma la petición (prompt versionado, payload seudonimizado,
 * esquema JSON), valida la respuesta (JSON Schema + reglas semánticas + ausencia de PII) y reintenta
 * si es inválida. Nunca lanza por fallos de la IA: el llamador recibe el motivo y aplica el
 * fallback al motor de reglas.
 */
@Service
public class StructuredCompletion {

    static final String ENABLED_KEY = "ai.enabled";
    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private static final Logger log = LoggerFactory.getLogger(StructuredCompletion.class);

    private final LlmGateway gateway;
    private final AiProperties properties;
    private final AiOutputValidator validator;
    private final PromptCatalog prompts;
    private final PayloadWhitelist whitelist;
    private final PiiRedactor redactor;
    private final AiUsageService usage;
    private final SettingsProvider settings;
    private final ObjectMapper mapper;

    public StructuredCompletion(LlmGateway gateway, AiProperties properties, AiOutputValidator validator,
                                PromptCatalog prompts, PayloadWhitelist whitelist, PiiRedactor redactor,
                                AiUsageService usage, SettingsProvider settings, ObjectMapper mapper) {
        this.gateway = gateway;
        this.properties = properties;
        this.validator = validator;
        this.prompts = prompts;
        this.whitelist = whitelist;
        this.redactor = redactor;
        this.usage = usage;
        this.settings = settings;
        this.mapper = mapper;
    }

    /**
     * @param output salida válida, o vacía
     * @param call   trazabilidad
     */
    public record Result(Optional<JsonNode> output, AiCallInfo call) {
    }

    /**
     * @param purpose       propósito para la bitácora (RISK_EXPLAIN, SUPPORT_PLAN)
     * @param promptVersion prompt versionado
     * @param schemaName    esquema de salida
     * @param tier          modelo económico o capaz
     * @param payload       payload del anonimizador
     * @param semantic      validaciones propias del caso (p. ej. recursos citables); devuelve errores
     */
    public Result complete(String purpose, String promptVersion, String schemaName, ModelTier tier,
                           AnonymizedPayload payload, Function<JsonNode, List<String>> semantic) {
        String model = properties.modelFor(tier);
        if (!settings.getBoolean(ENABLED_KEY, true)) {
            return failure(model, promptVersion, payload, AiFailure.DISABLED, 0, 0, 0);
        }
        if (!properties.configured()) {
            return failure(model, promptVersion, payload, AiFailure.NOT_CONFIGURED, 0, 0, 0);
        }
        try {
            // Defensa en profundidad: el payload se vuelve a validar justo antes de salir.
            whitelist.validate(mapper.readTree(payload.json()));
        } catch (PayloadWhitelist.PayloadPolicyException | JsonProcessingException ex) {
            log.warn("Payload bloqueado por la política de privacidad (propósito {})", purpose);
            usage.logCall(purpose, model, promptVersion, payload.sha256(), AiFailure.POLICY_VIOLATION.name(), 0, 0, 0, 0);
            return failure(model, promptVersion, payload, AiFailure.POLICY_VIOLATION, 0, 0, 0);
        }

        MessageCreateParams params = params(model, tier, promptVersion, schemaName, payload);
        int maxAttempts = 1 + Math.max(0, properties.invalidOutputRetries());
        long inputTokens = 0;
        long outputTokens = 0;
        String respondingModel = model;
        AiFailure failure = null;
        JsonNode valid = null;
        int attempt = 0;
        long started = System.nanoTime();
        while (attempt < maxAttempts) {
            attempt++;
            Message message;
            try {
                message = gateway.send(purpose, params);
            } catch (LlmException ex) {
                failure = ex.reason();
                break;
            }
            inputTokens += message.usage().inputTokens();
            outputTokens += message.usage().outputTokens();
            respondingModel = message.model().asString();
            if (message.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
                failure = AiFailure.REFUSAL;
                break;
            }
            List<String> errors = new ArrayList<>();
            JsonNode node = parse(text(message), errors);
            if (node != null) {
                errors.addAll(validator.validate(schemaName, node));
                if (errors.isEmpty()) {
                    errors.addAll(piiFindings(node));
                    errors.addAll(semantic.apply(node));
                }
            }
            if (errors.isEmpty()) {
                valid = node;
                failure = null;
                break;
            }
            failure = AiFailure.INVALID_OUTPUT;
            log.info("Salida de IA inválida (propósito {}, intento {}/{}): {}", purpose, attempt, maxAttempts,
                    errors.size() > 5 ? errors.subList(0, 5) : errors);
        }
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        usage.logCall(purpose, respondingModel, promptVersion, payload.sha256(),
                failure == null ? "OK" : failure.name(), attempt, inputTokens, outputTokens, latencyMs);
        AiCallInfo info = new AiCallInfo(respondingModel, promptVersion, payload.sha256(), inputTokens, outputTokens,
                attempt, failure, payload.sensitiveContext());
        return new Result(Optional.ofNullable(valid), info);
    }

    private MessageCreateParams params(String model, ModelTier tier, String promptVersion, String schemaName,
                                       AnonymizedPayload payload) {
        Map<String, JsonValue> schema = new LinkedHashMap<>();
        validator.apiSchema(schemaName).forEach((k, v) -> schema.put(k, JsonValue.from(v)));
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(properties.maxTokens())
                // El prompt del sistema es estable: se marca para caché y el payload va después.
                .systemOfTextBlockParams(List.of(TextBlockParam.builder().text(prompts.text(promptVersion))
                        .cacheControl(CacheControlEphemeral.builder().build()).build()))
                .addUserMessage("Caso seudonimizado (JSON):\n" + payload.json())
                .outputConfig(OutputConfig.builder()
                        .effort(OutputConfig.Effort.of(properties.effortFor(tier)))
                        .format(JsonOutputFormat.builder()
                                .schema(JsonOutputFormat.Schema.builder().putAllAdditionalProperties(schema).build())
                                .build())
                        .build());
        if (tier == ModelTier.DEFAULT && properties.refusalFallback()) {
            // Si el modelo capaz declina, el servidor reintenta con otro modelo según la categoría.
            builder.putAdditionalHeader("anthropic-beta", FALLBACK_BETA);
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return builder.build();
    }

    static String text(Message message) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : message.content()) {
            block.text().ifPresent(t -> sb.append(t.text()));
        }
        return sb.toString().strip();
    }

    private JsonNode parse(String text, List<String> errors) {
        String body = text;
        if (body.startsWith("```")) {
            body = body.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        try {
            JsonNode node = mapper.readTree(body);
            if (node == null || !node.isObject()) {
                errors.add("$: no es un objeto JSON");
                return null;
            }
            return node;
        } catch (JsonProcessingException ex) {
            errors.add("$: JSON inválido");
            return null;
        }
    }

    /** La salida tampoco puede traer PII (la IA podría inventar un teléfono o un nombre). */
    private List<String> piiFindings(JsonNode node) {
        List<String> findings = new ArrayList<>();
        walk(node, "$", findings);
        return findings;
    }

    private void walk(JsonNode node, String path, List<String> findings) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                walk(e.getValue(), path + "." + e.getKey(), findings);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(node.get(i), path + "[" + i + "]", findings);
            }
        } else if (node.isTextual()) {
            redactor.detect(node.asText()).forEach(kind -> findings.add(path + " contiene " + kind));
        }
    }

    private Result failure(String model, String promptVersion, AnonymizedPayload payload, AiFailure reason,
                           int attempts, long in, long out) {
        return new Result(Optional.empty(), new AiCallInfo(model, promptVersion, payload.sha256(), in, out, attempts,
                reason, payload.sensitiveContext()));
    }
}
