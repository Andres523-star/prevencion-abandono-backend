package co.edu.retencion.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import co.edu.retencion.ai.domain.PayloadWhitelist;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.support.AttendanceWorld;
import co.edu.retencion.support.AttendanceWorld.Actor;
import co.edu.retencion.support.FakeClaude;
import co.edu.retencion.support.RiskIntegrationTest;

/**
 * Criterios de aceptación de la Fase 4 con la API de Claude simulada (WireMock): JSON válido,
 * inválido, timeout, 429, fallback a reglas, ausencia de PII, lista blanca, historial y cambio de
 * umbrales sin redeploy.
 */
class RiskEngineIT extends RiskIntegrationTest {

    private static String validAlto() {
        return FakeClaude.validExplanation("ALTO", "TUT-ACADEMICA");
    }

    @Test
    void validJsonProducesAHybridAssessmentWithFullTraceability() throws Exception {
        AttendanceWorld w = highRiskWorld(ISW);
        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of(ISW));
        CLAUDE.willReturnExplanation("claude-opus-5-5", validAlto());

        JsonNode a = analyze(counselor, w.studentId());

        assertThat(a.get("level").asText()).isEqualTo("ALTO");
        assertThat(a.get("source").asText()).isEqualTo("HYBRID");
        assertThat(a.get("scoreType").asText()).isEqualTo("RULES");
        assertThat(a.get("probability").isNull()).isTrue();
        assertThat(a.get("scoreMeaning").asText()).contains("No es una probabilidad");
        assertThat(a.get("requiresHumanReview").asBoolean()).isTrue();
        assertThat(a.get("factors").findValuesAsText("code")).contains("ABSENCES", "LOW_AVERAGE", "FAILING_SUBJECTS");
        JsonNode ai = a.get("ai");
        assertThat(ai.get("summary").asText()).isNotBlank();
        assertThat(ai.get("recommendations").get(0).get("resourceName").asText()).isEqualTo("Tutorías académicas");
        JsonNode v = a.get("versions");
        assertThat(v.get("aiModel").asText()).isEqualTo("claude-opus-5-5");
        assertThat(v.get("promptVersion").asText()).isEqualTo("risk-explainer.v1");
        assertThat(v.get("payloadHash").asText()).hasSize(64);
        assertThat(v.get("rulesVersion").asText()).startsWith("rules-");
        assertThat(v.get("inputTokens").asInt()).isEqualTo(1200);
        assertThat(v.get("outputTokens").asInt()).isEqualTo(300);

        // Riesgo ALTO → modelo capaz, salida estructurada con esquema y fallback del servidor ante rechazos.
        JsonNode request = CLAUDE.requests().get(0);
        assertThat(request.get("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(request.at("/output_config/format/schema/properties/recommendations").isObject()).isTrue();
        assertThat(request.at("/output_config/effort").asText()).isEqualTo("medium");
        assertThat(request.get("fallbacks").asText()).isEqualTo("default");
        Integer logged = jdbc.queryForObject("SELECT count(*) FROM ai_calls WHERE payload_hash = ? AND outcome = 'OK'",
                Integer.class, v.get("payloadHash").asText());
        assertThat(logged).isEqualTo(1);
    }

    @Test
    void everyAnalysisIsANewHistoryRow() throws Exception {
        AttendanceWorld w = highRiskWorld(ISW);
        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of(ISW));
        CLAUDE.willReturnExplanation("claude-opus-5-5", validAlto());

        JsonNode first = analyze(counselor, w.studentId());
        JsonNode second = analyze(counselor, w.studentId());
        assertThat(assessmentsOf(w.studentId())).isEqualTo(2);
        assertThat(first.get("id")).isNotEqualTo(second.get("id"));

        JsonNode page = read(mvc.perform(get("/api/v1/students/" + w.studentId() + "/risk-assessments")
                .header("Authorization", bearer(counselor.tokens())).param("size", "1")).andExpect(status().isOk()));
        assertThat(page.get("items").get(0).get("id")).isEqualTo(second.get("id"));
        JsonNode next = read(mvc.perform(get("/api/v1/students/" + w.studentId() + "/risk-assessments")
                .header("Authorization", bearer(counselor.tokens())).param("after", page.get("nextCursor").asText()))
                .andExpect(status().isOk()));
        assertThat(next.get("items").get(0).get("id")).isEqualTo(first.get("id"));
        // La tabla es append-only para la aplicación (sin UPDATE).
        Integer state = jdbc.queryForObject("SELECT count(*) FROM risk_student_state WHERE student_id = ? "
                + "AND last_assessment_id = ?", Integer.class, w.studentId(), UUID.fromString(second.get("id").asText()));
        assertThat(state).isEqualTo(1);
    }

    @Test
    void invalidJsonIsRetriedOnceAndThenFallsBackToRules() throws Exception {
        AttendanceWorld w = highRiskWorld(ISW);
        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of(ISW));

        CLAUDE.willRespondInSequence(List.of(
                FakeClaude.ok(FakeClaude.textMessage("claude-opus-5-5", "Esto no es JSON", "end_turn")),
                FakeClaude.ok(FakeClaude.textMessage("claude-opus-5-5", validAlto(), "end_turn"))));
        JsonNode recovered = analyze(counselor, w.studentId());
        assertThat(recovered.get("source").asText()).isEqualTo("HYBRID");
        assertThat(recovered.at("/versions/aiAttempts").asInt()).isEqualTo(2);

        CLAUDE.reset();
        CLAUDE.willRespond(FakeClaude.ok(FakeClaude.textMessage("claude-opus-5-5",
                "{\"level\":\"ALTO\",\"summary\":\"falta todo\"}", "end_turn")));
        JsonNode fallback = analyze(counselor, w.studentId());
        assertThat(fallback.get("source").asText()).isEqualTo("RULES");
        assertThat(fallback.get("fallbackReason").asText()).isEqualTo("INVALID_OUTPUT");
        assertThat(fallback.at("/versions/aiAttempts").asInt()).isEqualTo(2);
        assertThat(fallback.get("ai").isNull()).isTrue();
        // El análisis de reglas se guarda igual, con sus factores.
        assertThat(fallback.get("factors").size()).isPositive();
        assertThat(CLAUDE.requestCount()).isEqualTo(2);
    }

    @Test
    void recommendationsCitingResourcesOutsideTheCatalogAreRejected() throws Exception {
        AttendanceWorld w = highRiskWorld(ISW);
        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of(ISW));
        CLAUDE.willReturnExplanation("claude-opus-5-5", FakeClaude.validExplanation("ALTO", "BECA-INVENTADA"));
        JsonNode a = analyze(counselor, w.studentId());
        assertThat(a.get("source").asText()).isEqualTo("RULES");
        assertThat(a.get("fallbackReason").asText()).isEqualTo("INVALID_OUTPUT");
    }

    @Test
    void timeoutFallsBackToRules() throws Exception {
        AttendanceWorld w = highRiskWorld(ISW);
        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of(ISW));
        CLAUDE.willRespond(FakeClaude.ok(FakeClaude.textMessage("claude-opus-5-5", validAlto(), "end_turn"))
                .withFixedDelay(4000));
        JsonNode a = analyze(counselor, w.studentId());
        assertThat(a.get("source").asText()).isEqualTo("RULES");
        assertThat(a.get("fallbackReason").asText()).isEqualTo("TIMEOUT");
        assertThat(a.get("level").asText()).isEqualTo("ALTO");
    }

    @Test
    void rateLimitAndServerErrorsFallBackToRules() throws Exception {
        AttendanceWorld w = highRiskWorld(ISW);
        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of(ISW));
        CLAUDE.willRespond(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(429)
                .withHeader("Content-Type", "application/json").withHeader("retry-after", "1")
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"Demasiadas peticiones\"}}"));
        JsonNode limited = analyze(counselor, w.studentId());
        assertThat(limited.get("source").asText()).isEqualTo("RULES");
        assertThat(limited.get("fallbackReason").asText()).isEqualTo("RATE_LIMITED");

        CLAUDE.reset();
        CLAUDE.willRespond(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(529)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Sobrecarga\"}}"));
        assertThat(analyze(counselor, w.studentId()).get("fallbackReason").asText()).isIn("UNAVAILABLE", "ERROR");

        CLAUDE.reset();
        CLAUDE.willRespond(FakeClaude.ok(FakeClaude.textMessage("claude-opus-5-5", "", "refusal")));
        assertThat(analyze(counselor, w.studentId()).get("fallbackReason").asText()).isEqualTo("REFUSAL");
        assertThat(assessmentsOf(w.studentId())).isEqualTo(3);
    }

    @Test
    void dailyTokenBudgetAndKillSwitchFallBackWithoutCallingTheApi() throws Exception {
        AttendanceWorld w = highRiskWorld(ISW);
        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of(ISW));
        CLAUDE.willReturnExplanation("claude-opus-5-5", validAlto());
        assertThat(analyze(counselor, w.studentId()).get("source").asText()).isEqualTo("HYBRID");
        int calls = CLAUDE.requestCount();

        setting("ai.daily-token-budget", 1000);
        JsonNode budget = analyze(counselor, w.studentId());
        assertThat(budget.get("fallbackReason").asText()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(CLAUDE.requestCount()).isEqualTo(calls);

        setting("ai.daily-token-budget", 2_000_000_000L);
        setting("ai.enabled", false);
        JsonNode off = analyze(counselor, w.studentId());
        assertThat(off.get("fallbackReason").asText()).isEqualTo("DISABLED");
        assertThat(CLAUDE.requestCount()).isEqualTo(calls);
    }

    @Test
    void changingAThresholdInSettingsChangesTheResultWithoutRedeploy() throws Exception {
        AttendanceWorld w = highRiskWorld(ISW);
        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of(ISW));
        setting("ai.enabled", false);
        JsonNode before = analyze(counselor, w.studentId());

        setting("risk.rules.absences", Map.of("enabled", true, "warningPoints", 8, "criticalPoints", 30,
                "lostPoints", 40, "maxPoints", 40));
        JsonNode after = analyze(counselor, w.studentId());
        assertThat(after.get("score").decimalValue()).isGreaterThan(before.get("score").decimalValue());
        assertThat(after.at("/versions/rulesVersion").asText()).isNotEqualTo(before.at("/versions/rulesVersion").asText());

        setting("risk.levels", Map.of("medium", 90, "high", 99));
        assertThat(analyze(counselor, w.studentId()).get("level").asText()).isEqualTo("BAJO");
    }

    /** Test obligatorio: ningún payload hacia la IA contiene PII y solo lleva campos de la lista blanca. */
    @Test
    void noPersonalDataEverReachesTheAiAndOnlyWhitelistedFieldsAreSent() throws Exception {
        AttendanceWorld w = world(ISW);
        String fullName = "Juan Camilo Pérez Gómez";
        String document = String.valueOf(1_000_000_000L + (long) (Math.random() * 8_999_999_999L));
        String email = "juan.perez" + UUID.randomUUID().toString().substring(0, 6) + "@" + STUDENT_DOMAIN;
        String phone = "3105551234";
        Map<String, Object> identity = new HashMap<>();
        identity.put("fullName", fullName);
        identity.put("documentType", "CC");
        identity.put("documentNumber", document);
        identity.put("email", email);
        identity.put("phone", phone);
        UUID studentId = UUID.fromString(read(postJson("/api/v1/students", w.admin().tokens(), Map.of(
                "identity", identity, "programId", ISW, "currentSemester", 4, "status", "ACTIVO",
                "admissionDate", "2024-08-01", "socioeconomic", Map.of("stratum", 1, "works", true, "dependents", 3,
                        "transportMode", "TRANSPORTE_PUBLICO")))
                .andExpect(status().isCreated())).get("id").asText());
        UUID enrollmentId = UUID.fromString(read(postJson("/api/v1/students/" + studentId + "/enrollments",
                w.admin().tokens(), Map.of("groupId", w.groupId())).andExpect(status().isCreated())).get("id").asText());
        for (int i = 0; i < 3; i++) {
            at(w.sessionEnd(i).plusSeconds(3600), w);
            mark(w.teacher().tokens(), w.sessionId(i), enrollmentId, "FALTA", null).andExpect(status().isOk());
        }
        // Observación con datos personales en texto libre (la redacta el anonimizador).
        postJson("/api/v1/students/" + studentId + "/observations", w.teacher().tokens(), Map.of("groupId", w.groupId(),
                "type", "ACADEMICA", "text", "Hablé con " + fullName + " (CC " + document + "), su mamá llamó al "
                        + phone + " y escribió desde " + email))
                .andExpect(status().isCreated());
        setting("ai.free-text.enabled", true);
        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of(ISW));
        CLAUDE.willReturnExplanation("claude-opus-5-5", validAlto());

        analyze(counselor, studentId);

        assertThat(CLAUDE.requestCount()).isPositive();
        for (JsonNode request : CLAUDE.requests()) {
            String body = request.toString();
            assertThat(body).doesNotContain("Juan", "Camilo", "Pérez", "Gómez", document, email, phone, "310 555",
                    "\"stratum\"", "estrato");
        }
        List<JsonNode> payloads = CLAUDE.userPayloads();
        assertThat(payloads).isNotEmpty();
        for (JsonNode payload : payloads) {
            assertThat(paths(payload)).allMatch(PayloadWhitelist.RISK_PAYLOAD::contains);
            assertThat(payload.toString()).doesNotContain("Juan", "Pérez", "Gómez", document, email, phone);
            assertThat(payload.get("caseId").asText()).matches(PayloadWhitelist.ANON_CODE);
            assertThat(payload.has("wellbeing")).isFalse();
        }
        assertThat(payloads.get(0).get("observations").get(0).asText()).contains("[NOMBRE]", "[NUMERO]", "[CORREO]");

        // Con el texto libre desactivado (por defecto) ni siquiera sale redactado.
        setting("ai.free-text.enabled", false);
        CLAUDE.reset();
        CLAUDE.willReturnExplanation("claude-opus-5-5", validAlto());
        analyze(counselor, studentId);
        assertThat(CLAUDE.userPayloads().get(0).has("observations")).isFalse();
    }

    @Test
    void teachersSeeOnlyAcademicReasonsAndNoAiTextAndOnlyForTheirGroups() throws Exception {
        AttendanceWorld w = highRiskWorld(ISW);
        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of(ISW));
        CLAUDE.willReturnExplanation("claude-opus-5-5", validAlto());
        analyze(counselor, w.studentId());

        relogin(w.teacher());
        JsonNode teacherView = read(mvc.perform(get("/api/v1/students/" + w.studentId() + "/risk-assessments/latest")
                .header("Authorization", bearer(w.teacher().tokens()))).andExpect(status().isOk()));
        assertThat(teacherView.get("factors").findValuesAsText("category")).isNotEmpty()
                .allMatch(c -> c.equals("ACADEMICO") || c.equals("ASISTENCIA"));
        assertThat(teacherView.get("ai").isNull()).isTrue();
        // El consejero sí ve el contexto socioeconómico; nadie salvo psicología ve bienestar.
        JsonNode counselorView = read(mvc.perform(get("/api/v1/students/" + w.studentId() + "/risk-assessments/latest")
                .header("Authorization", bearer(counselor.tokens()))).andExpect(status().isOk()));
        assertThat(counselorView.get("factors").findValuesAsText("category")).contains("SOCIOECONOMICO");

        Actor otherTeacher = staff(Set.of(Role.DOCENTE), Set.of());
        mvc.perform(get("/api/v1/students/" + w.studentId() + "/risk-assessments/latest")
                .header("Authorization", bearer(otherTeacher.tokens()))).andExpect(status().isForbidden());
        Actor otherCoordinator = staff(Set.of(Role.COORDINADOR), Set.of(MED));
        mvc.perform(post("/api/v1/students/" + w.studentId() + "/analyze")
                .header("Authorization", bearer(otherCoordinator.tokens()))).andExpect(status().isForbidden());
        relogin(w.student());
        mvc.perform(get("/api/v1/students/" + w.studentId() + "/risk-assessments")
                .header("Authorization", bearer(w.student().tokens()))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/students/" + w.studentId() + "/analyze")
                .header("Authorization", bearer(w.teacher().tokens()))).andExpect(status().isForbidden());
    }

    static List<String> paths(JsonNode root) {
        List<String> out = new ArrayList<>();
        collect(root, "", out);
        return out;
    }

    private static void collect(JsonNode node, String path, List<String> out) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String child = path.isEmpty() ? e.getKey() : path + "." + e.getKey();
                out.add(child);
                collect(e.getValue(), child, out);
            }
        } else if (node.isArray()) {
            out.add(path + "[]");
            node.forEach(n -> collect(n, path + "[]", out));
        }
    }
}
