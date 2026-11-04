package co.edu.retencion.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * API de Claude simulada con WireMock ({@code POST /v1/messages}): respuestas válidas, JSON
 * inválido, demoras (timeout), 429, 5xx y secuencias (reintentos, vueltas del agente). Guarda las
 * peticiones para verificar que ningún payload lleva PII ni campos fuera de la lista blanca.
 */
public final class FakeClaude {

    public static final String API_KEY = "sk-ant-prueba-no-real";
    static final ObjectMapper MAPPER = new ObjectMapper();
    private static FakeClaude instance;

    private final WireMockServer server;

    private FakeClaude() {
        server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
    }

    public static synchronized FakeClaude get() {
        if (instance == null) {
            instance = new FakeClaude();
        }
        return instance;
    }

    public void register(DynamicPropertyRegistry registry) {
        registry.add("ai.base-url", server::baseUrl);
        registry.add("ai.api-key", () -> API_KEY);
    }

    public void reset() {
        server.resetAll();
    }

    /** Respuesta de texto (la salida estructurada llega como un bloque de texto con JSON). */
    public static String textMessage(String model, String text, String stopReason) {
        ObjectNode m = envelope(model, stopReason);
        ((ArrayNode) m.get("content")).addObject().put("type", "text").put("text", text);
        return m.toString();
    }

    /** Respuesta con llamadas a herramientas (agente). */
    public static String toolUseMessage(String model, String toolUseId, String tool, Object input) {
        ObjectNode m = envelope(model, "tool_use");
        ObjectNode block = ((ArrayNode) m.get("content")).addObject();
        block.put("type", "tool_use").put("id", toolUseId).put("name", tool);
        block.set("input", MAPPER.valueToTree(input));
        return m.toString();
    }

    private static ObjectNode envelope(String model, String stopReason) {
        ObjectNode m = MAPPER.createObjectNode();
        m.put("id", "msg_" + System.nanoTime()).put("type", "message").put("role", "assistant").put("model", model);
        m.putArray("content");
        m.put("stop_reason", stopReason).putNull("stop_sequence");
        m.putObject("usage").put("input_tokens", 1200).put("output_tokens", 300);
        return m;
    }

    public static ResponseDefinitionBuilder ok(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    /** Todas las peticiones responden igual. */
    public void willRespond(ResponseDefinitionBuilder response) {
        server.stubFor(post(urlEqualTo("/v1/messages")).willReturn(response));
    }

    /** Las peticiones reciben las respuestas en orden; la última se repite. */
    public void willRespondInSequence(List<ResponseDefinitionBuilder> responses) {
        String state = Scenario.STARTED;
        for (int i = 0; i < responses.size(); i++) {
            String next = i == responses.size() - 1 ? state : "paso-" + (i + 1);
            server.stubFor(post(urlEqualTo("/v1/messages")).inScenario("secuencia").whenScenarioStateIs(state)
                    .willReturn(responses.get(i)).willSetStateTo(next));
            state = next;
        }
    }

    /** Responde así a las peticiones cuyo cuerpo contiene el fragmento (p. ej. una frase del prompt). */
    public void willRespondWhenBodyContains(String fragment, ResponseDefinitionBuilder response) {
        server.stubFor(post(urlEqualTo("/v1/messages")).atPriority(1)
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing(fragment))
                .willReturn(response));
    }

    /** Plan de apoyo válido según support-plan.v1. */
    public static String validPlan(String studentMessage) {
        try {
            Map<String, Object> contact = new java.util.LinkedHashMap<>();
            contact.put("type", "CONTACTO_DOCENTE");
            contact.put("title", "Contacto del docente");
            contact.put("description", "Conversar con el estudiante sobre sus faltas en {{materia}}.");
            contact.put("priority", "ALTA");
            contact.put("suggestedOwner", "DOCENTE");
            contact.put("resourceId", null);
            contact.put("dueInDays", 2);
            Map<String, Object> tutoring = new java.util.LinkedHashMap<>(contact);
            tutoring.put("type", "TUTORIA");
            tutoring.put("title", "Tutorías");
            tutoring.put("description", "Tutorías para ponerse al día con los temas perdidos.");
            tutoring.put("priority", "MEDIA");
            tutoring.put("suggestedOwner", "CONSEJERO");
            tutoring.put("resourceId", "TUT-ACADEMICA");
            tutoring.put("dueInDays", 7);
            return MAPPER.writeValueAsString(Map.of("actions", List.of(contact, tutoring), "studentMessage", studentMessage));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public void willReturnExplanation(String model, String explanationJson) {
        willRespond(ok(textMessage(model, explanationJson, "end_turn")));
    }

    public int requestCount() {
        return server.getAllServeEvents().size();
    }

    /** Cuerpos JSON de las peticiones recibidas, de la más antigua a la más reciente. */
    public List<JsonNode> requests() {
        List<JsonNode> out = new ArrayList<>();
        List<ServeEvent> events = new ArrayList<>(server.getAllServeEvents());
        java.util.Collections.reverse(events);
        for (ServeEvent e : events) {
            try {
                out.add(MAPPER.readTree(e.getRequest().getBodyAsString()));
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException(ex);
            }
        }
        return out;
    }

    /** Payloads JSON enviados en los mensajes de usuario (texto tras la primera línea) y salidas de herramientas. */
    public List<JsonNode> userPayloads() {
        List<JsonNode> payloads = new ArrayList<>();
        for (JsonNode request : requests()) {
            for (JsonNode message : request.path("messages")) {
                if (!"user".equals(message.path("role").asText())) {
                    continue;
                }
                List<String> texts = new ArrayList<>();
                if (message.path("content").isTextual()) {
                    texts.add(message.path("content").asText());
                } else {
                    for (JsonNode block : message.path("content")) {
                        if (block.has("text")) {
                            texts.add(block.path("text").asText());
                        }
                        if ("tool_result".equals(block.path("type").asText())) {
                            JsonNode content = block.path("content");
                            texts.add(content.isTextual() ? content.asText()
                                    : content.path(0).path("text").asText());
                        }
                    }
                }
                for (String text : texts) {
                    int brace = text.indexOf('{');
                    if (brace >= 0) {
                        try {
                            payloads.add(MAPPER.readTree(text.substring(brace)));
                        } catch (JsonProcessingException ignored) {
                            // Texto sin JSON (instrucciones o errores de herramienta).
                        }
                    }
                }
            }
        }
        return payloads;
    }

    /** Explicación válida según el esquema risk-explanation.v1. */
    public static String validExplanation(String level, String resourceId) {
        Map<String, Object> rec = new java.util.LinkedHashMap<>();
        rec.put("resourceId", resourceId);
        rec.put("action", "Inscribir al estudiante en tutorías de la materia con más faltas.");
        rec.put("priority", "ALTA");
        rec.put("responsibleRole", "CONSEJERO");
        try {
            return MAPPER.writeValueAsString(Map.of(
                    "level", level,
                    "summary", "El caso combina faltas en nivel crítico con notas a la baja en una materia.",
                    "factors", List.of(Map.of("code", "ABSENCES", "weight", 0.6,
                            "explanation", "Tres de cuatro faltas permitidas.")),
                    "probableCauses", List.of(Map.of("category", "ACADEMICA",
                            "rationale", "Dificultad con los contenidos de la materia.")),
                    "recommendations", List.of(rec),
                    "confidence", 0.7));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
