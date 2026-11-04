package co.edu.retencion.ai.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import co.edu.retencion.ai.Anonymizer;
import co.edu.retencion.ai.ModelTier;
import co.edu.retencion.ai.ResourceCatalog;
import co.edu.retencion.ai.RetentionAgentTools;
import co.edu.retencion.ai.StudentTextPlaceholders;
import co.edu.retencion.ai.SupportResourceInfo;
import co.edu.retencion.ai.application.LlmGateway.LlmException;
import co.edu.retencion.ai.domain.AgentRun;
import co.edu.retencion.ai.domain.AgentRunRepository;
import co.edu.retencion.ai.domain.AgentToolCall;
import co.edu.retencion.ai.domain.AgentToolCallRepository;
import co.edu.retencion.ai.domain.AiPayload;
import co.edu.retencion.ai.domain.PayloadWhitelist;
import co.edu.retencion.ai.domain.PiiRedactor;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.settings.SettingsProvider;

/**
 * Agente de retención (desactivable con {@code ai.agent.enabled}). Con <i>tool use</i>, Claude
 * consulta herramientas de SOLO LECTURA sobre el caso seudonimizado y entrega un borrador con la
 * herramienta {@code submit_draft}. El agente no envía mensajes ni ejecuta acciones: el resultado es
 * un borrador para revisión humana.
 *
 * <p>Bucle manual (no el tool runner del SDK) para controlar cada vuelta: toda salida de herramienta
 * vuelve a pasar por la lista blanca, cada llamada queda registrada en {@code agent_tool_calls}, el
 * número de vueltas está acotado y el borrador se valida con JSON Schema antes de aceptarse. Las
 * herramientas no reciben id de estudiante: solo pueden leer el caso de la ejecución.
 */
@Service
public class RetentionAgentService {

    static final String ENABLED_KEY = "ai.agent.enabled";
    static final String PURPOSE = "RETENTION_AGENT";
    static final String DRAFT_SCHEMA = "retention-draft.v1";
    static final Set<String> RESOURCE_CATEGORIES = java.util.Arrays.stream(
            co.edu.retencion.ai.domain.SupportResource.Category.values()).map(Enum::name).collect(Collectors.toSet());

    private final RetentionAgentTools tools;
    private final ResourceCatalog catalog;
    private final Anonymizer anonymizer;
    private final LlmGateway gateway;
    private final AiProperties properties;
    private final PromptCatalog prompts;
    private final AiOutputValidator validator;
    private final PayloadWhitelist whitelist;
    private final PiiRedactor redactor;
    private final AgentRunRepository runs;
    private final AgentToolCallRepository toolCalls;
    private final AiUsageService usage;
    private final SettingsProvider settings;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RetentionAgentService(RetentionAgentTools tools, ResourceCatalog catalog, Anonymizer anonymizer,
                                 LlmGateway gateway, AiProperties properties, PromptCatalog prompts,
                                 AiOutputValidator validator, PayloadWhitelist whitelist, PiiRedactor redactor,
                                 AgentRunRepository runs, AgentToolCallRepository toolCalls, AiUsageService usage,
                                 SettingsProvider settings, ObjectMapper mapper, TransactionTemplate tx, Clock clock) {
        this.tools = tools;
        this.catalog = catalog;
        this.anonymizer = anonymizer;
        this.gateway = gateway;
        this.properties = properties;
        this.prompts = prompts;
        this.validator = validator;
        this.whitelist = whitelist;
        this.redactor = redactor;
        this.runs = runs;
        this.toolCalls = toolCalls;
        this.usage = usage;
        this.settings = settings;
        this.mapper = mapper;
        this.tx = tx;
        this.clock = clock;
    }

    /** Vista de una ejecución con su borrador y las herramientas que usó. */
    public record AgentRunView(UUID id, UUID studentId, AgentRun.Status status, String model, String promptVersion,
                               JsonNode draft, List<ToolCallView> toolCalls, int inputTokens, int outputTokens,
                               String failureReason, boolean requiresHumanReview, Instant createdAt,
                               Instant finishedAt) {
    }

    public record ToolCallView(int seq, String toolName, boolean error, Instant at) {
    }

    /** Ejecuta el agente y devuelve el borrador (o la ejecución fallida con su motivo). */
    public AgentRunView draft(UUID studentId, UUID requestedBy) {
        if (!settings.getBoolean(ENABLED_KEY, false)) {
            throw new DomainException(AiProblems.AGENT_DISABLED, "El agente de retención está desactivado");
        }
        RetentionAgentTools.AgentCase agentCase = tools.caseOf(studentId)
                .orElseThrow(() -> DomainException.notFound("Estudiante no encontrado"));
        List<SupportResourceInfo> eligible = catalog.eligible(agentCase.eligibility());
        Set<String> citable = eligible.stream().map(SupportResourceInfo::id).collect(Collectors.toSet());
        // El caso completo se anonimiza una vez; cada herramienta devuelve una proyección de él.
        JsonNode full = readTree(anonymizer.anonymize(agentCase.facts(), eligible, null).json());

        String model = properties.modelFor(ModelTier.DEFAULT);
        AgentRun run = tx.execute(s -> runs.save(AgentRun.start(studentId, model, PromptCatalog.RETENTION_AGENT,
                requestedBy, clock.instant())));
        if (!settings.getBoolean(StructuredCompletion.ENABLED_KEY, true) || !properties.configured()) {
            return finish(run, null, null, "NOT_CONFIGURED");
        }

        List<MessageParam> history = new ArrayList<>();
        ObjectNode opening = mapper.createObjectNode();
        opening.put("schemaVersion", AiPayload.SCHEMA_VERSION);
        opening.set("caseId", full.get("caseId"));
        opening.set("program", full.get("program"));
        opening.set("semester", full.get("semester"));
        whitelist.validate(opening);
        history.add(MessageParam.ofUser("Caso seudonimizado: " + opening + "\nPrepara el borrador del plan de retención."));

        int seq = 0;
        for (int iteration = 0; iteration < Math.max(1, properties.agentMaxIterations()); iteration++) {
            Message message;
            try {
                message = gateway.send(PURPOSE, params(model, history));
            } catch (LlmException ex) {
                usage.logCall(PURPOSE, model, PromptCatalog.RETENTION_AGENT, sha(full), ex.reason().name(),
                        iteration + 1, run.getInputTokens(), run.getOutputTokens(), 0);
                return finish(run, null, null, ex.reason().name());
            }
            run.addTokens(message.usage().inputTokens(), message.usage().outputTokens());
            history.add(message.toParam());
            if (message.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
                return finish(run, null, message.model().asString(), "REFUSAL");
            }
            List<ToolUseBlock> uses = message.content().stream().flatMap(b -> b.toolUse().stream()).toList();
            if (uses.isEmpty()) {
                history.add(MessageParam.ofUser("Cuando tengas la información, llama a submit_draft con el borrador."));
                continue;
            }
            List<ContentBlockParam> results = new ArrayList<>();
            for (ToolUseBlock use : uses) {
                seq++;
                JsonNode input = mapper.valueToTree(use._input().convert(Object.class));
                if ("submit_draft".equals(use.name())) {
                    List<String> errors = validateDraft(input, citable);
                    logTool(run, seq, use.name(), "{\"valid\":" + errors.isEmpty() + "}", "", !errors.isEmpty());
                    if (errors.isEmpty()) {
                        usage.logCall(PURPOSE, message.model().asString(), PromptCatalog.RETENTION_AGENT, sha(full),
                                "OK", iteration + 1, run.getInputTokens(), run.getOutputTokens(), 0);
                        return finish(run, input.toString(), message.model().asString(), null);
                    }
                    results.add(toolResult(use, "Borrador inválido, corrígelo: " + errors, true));
                    continue;
                }
                String output;
                boolean error = false;
                try {
                    output = execute(use.name(), input, full, studentId);
                } catch (IllegalArgumentException ex) {
                    output = "{\"error\":\"" + ex.getMessage() + "\"}";
                    error = true;
                }
                logTool(run, seq, use.name(), input.toString(), output, error);
                results.add(toolResult(use, output, error));
            }
            history.add(MessageParam.ofUserOfContentBlockParams(results));
        }
        usage.logCall(PURPOSE, model, PromptCatalog.RETENTION_AGENT, sha(full), "MAX_ITERATIONS",
                properties.agentMaxIterations(), run.getInputTokens(), run.getOutputTokens(), 0);
        return finish(run, null, null, "MAX_ITERATIONS");
    }

    public java.util.Optional<AgentRunView> find(UUID runId) {
        return runs.findById(runId).map(this::view);
    }

    /** Herramientas de solo lectura: cada salida es una proyección validada del caso. */
    String execute(String tool, JsonNode input, JsonNode full, UUID studentId) {
        ObjectNode out = mapper.createObjectNode();
        switch (tool) {
            case "get_academic_summary" -> {
                out.set("academic", full.get("academic"));
                out.set("subjects", full.get("subjects"));
                out.set("openCauses", full.get("openCauses"));
                if (full.has("context")) {
                    out.set("context", full.get("context"));
                }
                if (full.has("riskScore")) {
                    out.set("riskScore", full.get("riskScore"));
                }
            }
            case "get_attendance" -> {
                ArrayNode subjects = out.putArray("subjects");
                full.path("subjects").forEach(s -> {
                    ObjectNode n = subjects.addObject();
                    for (String f : List.of("ref", "name", "countedAbsences", "maxAbsences", "attendanceLevel",
                            "tardies", "pendingJustifications")) {
                        n.set(f, s.get(f));
                    }
                });
            }
            case "get_risk_history" -> {
                int limit = Math.max(1, Math.min(input.path("limit").asInt(5), 20));
                JsonNode history = readTree(AiPayload.builder(mapper, redactor, whitelist)
                        .history(tools.history(studentId, limit), clock.instant()).build().json());
                out.set("history", history.get("history"));
            }
            case "search_resources" -> {
                String category = input.path("category").isTextual() ? input.path("category").asText() : null;
                if (category != null && !RESOURCE_CATEGORIES.contains(category)) {
                    throw new IllegalArgumentException("categoria desconocida");
                }
                ArrayNode resources = out.putArray("resources");
                full.path("resources").forEach(r -> {
                    if (category == null || category.equals(r.path("category").asText())) {
                        resources.add(r);
                    }
                });
            }
            default -> throw new IllegalArgumentException("herramienta desconocida");
        }
        whitelist.validate(out);
        return out.toString();
    }

    private List<String> validateDraft(JsonNode draft, Set<String> citable) {
        List<String> errors = new ArrayList<>(validator.validate(DRAFT_SCHEMA, draft));
        if (!errors.isEmpty()) {
            return errors;
        }
        JsonNode actions = draft.path("actions");
        for (int i = 0; i < actions.size(); i++) {
            JsonNode id = actions.get(i).path("resourceId");
            if (id.isTextual() && !citable.contains(id.asText())) {
                errors.add("actions[" + i + "].resourceId no existe en el catálogo");
            }
        }
        if (!StudentTextPlaceholders.onlyAllowed(draft.path("studentMessage").asText())) {
            errors.add("studentMessage usa marcadores no permitidos");
        }
        List<String> texts = new ArrayList<>();
        draft.findValues("summary").forEach(n -> texts.add(n.asText()));
        draft.findValues("description").forEach(n -> texts.add(n.asText()));
        draft.findValues("title").forEach(n -> texts.add(n.asText()));
        draft.findValues("purpose").forEach(n -> texts.add(n.asText()));
        texts.add(draft.path("studentMessage").asText());
        texts.forEach(t -> redactor.detect(t).forEach(kind -> errors.add("el borrador contiene " + kind)));
        return errors;
    }

    private MessageCreateParams params(String model, List<MessageParam> history) {
        return MessageCreateParams.builder()
                .model(model)
                .maxTokens(properties.maxTokens())
                .systemOfTextBlockParams(List.of(TextBlockParam.builder().text(prompts.text(PromptCatalog.RETENTION_AGENT))
                        .cacheControl(CacheControlEphemeral.builder().build()).build()))
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(properties.effortFor(ModelTier.DEFAULT)))
                        .build())
                .tools(agentTools().stream().map(com.anthropic.models.messages.ToolUnion::ofTool).toList())
                .messages(history)
                .build();
    }

    /** Definición de herramientas (lista fija y ordenada: el prefijo de la petición es estable para caché). */
    List<Tool> agentTools() {
        Map<String, Object> draft = validator.apiSchema(DRAFT_SCHEMA);
        Tool.InputSchema.Properties.Builder draftProps = Tool.InputSchema.Properties.builder();
        ((Map<?, ?>) draft.get("properties")).forEach((k, v) -> draftProps.putAdditionalProperty((String) k,
                JsonValue.from(v)));
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) draft.get("required");
        return List.of(
                noArgs("get_academic_summary", "Notas, tendencias, faltas por materia, causas abiertas y puntaje del caso."),
                noArgs("get_attendance", "Faltas que cuentan, límite y nivel de asistencia por materia."),
                Tool.builder().name("get_risk_history").description("Análisis de riesgo anteriores, del más reciente al más antiguo.")
                        .strict(true)
                        .inputSchema(Tool.InputSchema.builder()
                                .properties(Tool.InputSchema.Properties.builder()
                                        .putAdditionalProperty("limit", JsonValue.from(Map.of("type", "integer")))
                                        .build())
                                .required(List.of("limit"))
                                .putAdditionalProperty("additionalProperties", JsonValue.from(false)).build())
                        .build(),
                Tool.builder().name("search_resources")
                        .description("Servicios de apoyo para los que el estudiante es elegible; category null = todos.")
                        .strict(true)
                        .inputSchema(Tool.InputSchema.builder()
                                .properties(Tool.InputSchema.Properties.builder()
                                        .putAdditionalProperty("category", JsonValue.from(Map.of("anyOf", List.of(
                                                Map.of("type", "string", "enum",
                                                        RESOURCE_CATEGORIES.stream().sorted().toList()),
                                                Map.of("type", "null")))))
                                        .build())
                                .required(List.of("category"))
                                .putAdditionalProperty("additionalProperties", JsonValue.from(false)).build())
                        .build(),
                Tool.builder().name("submit_draft")
                        .description("Entrega el borrador final del plan de retención para revisión humana.")
                        .strict(true)
                        .inputSchema(Tool.InputSchema.builder().properties(draftProps.build()).required(required)
                                .putAdditionalProperty("additionalProperties", JsonValue.from(false)).build())
                        .build());
    }

    private static Tool noArgs(String name, String description) {
        return Tool.builder().name(name).description(description)
                .inputSchema(Tool.InputSchema.builder().properties(Tool.InputSchema.Properties.builder().build())
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false)).build())
                .build();
    }

    private static ContentBlockParam toolResult(ToolUseBlock use, String content, boolean error) {
        return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(use.id()).content(content)
                .isError(error).build());
    }

    private void logTool(AgentRun run, int seq, String name, String inputJson, String output, boolean error) {
        run.toolCalled();
        tx.executeWithoutResult(s -> toolCalls.save(AgentToolCall.of(run.getId(), seq, name, inputJson,
                AiPayload.sha256(output), output.length(), error, clock.instant())));
    }

    private AgentRunView finish(AgentRun run, String draftJson, String respondingModel, String failure) {
        if (failure == null) {
            run.completeWithDraft(draftJson, respondingModel, clock.instant());
        } else {
            run.fail(failure, clock.instant());
        }
        AgentRun saved = tx.execute(s -> runs.save(run));
        return view(saved);
    }

    private AgentRunView view(AgentRun r) {
        List<ToolCallView> calls = toolCalls.findByRunIdOrderBySeqAsc(r.getId()).stream()
                .map(c -> new ToolCallView(c.getSeq(), c.getToolName(), c.isError(), c.getCreatedAt())).toList();
        return new AgentRunView(r.getId(), r.getStudentId(), r.getStatus(), r.getModel(), r.getPromptVersion(),
                r.getDraft() == null ? null : readTree(r.getDraft()), calls, r.getInputTokens(), r.getOutputTokens(),
                r.getFailureReason(), true, r.getCreatedAt(), r.getFinishedAt());
    }

    private JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("JSON interno inválido", ex);
        }
    }

    private static String sha(JsonNode full) {
        return AiPayload.sha256(full.toString());
    }
}
