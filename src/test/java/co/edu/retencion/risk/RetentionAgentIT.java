package co.edu.retencion.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import co.edu.retencion.ai.domain.PayloadWhitelist;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.support.AttendanceWorld;
import co.edu.retencion.support.AttendanceWorld.Actor;
import co.edu.retencion.support.FakeClaude;
import co.edu.retencion.support.RiskIntegrationTest;

/**
 * Agente de retención con tool use: solo herramientas de lectura sobre datos seudonimizados, cada
 * llamada registrada, y el resultado es un borrador para revisión humana.
 */
class RetentionAgentIT extends RiskIntegrationTest {

    static Map<String, Object> draft(String resourceId) {
        Map<String, Object> action = new java.util.LinkedHashMap<>();
        action.put("type", "CONSEJERIA");
        action.put("title", "Cita de consejería");
        action.put("description", "Revisar la carga académica y las barreras de asistencia.");
        action.put("priority", "ALTA");
        action.put("suggestedOwner", "CONSEJERO");
        action.put("resourceId", resourceId);
        action.put("dueInDays", 3);
        return Map.of("summary", "Faltas en nivel crítico y notas bajas en una materia; se propone acompañamiento.",
                "actions", List.of(action),
                "followUps", List.of(Map.of("dayOffset", 7, "purpose", "Revisar asistencia de la semana")),
                "studentMessage", "Hola {{nombre}}, queremos acompañarte en {{materia}}.");
    }

    @Test
    void agentUsesReadOnlyToolsAndProducesADraftForHumanReview() throws Exception {
        AttendanceWorld w = highRiskWorld(ISW);
        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of(ISW));
        String path = "/api/v1/students/" + w.studentId() + "/retention-agent/drafts";

        // Desactivado por defecto.
        mvc.perform(post(path).header("Authorization", bearer(counselor.tokens()))).andExpect(status().isConflict());
        setting("ai.agent.enabled", true);
        relogin(w.teacher());
        mvc.perform(post(path).header("Authorization", bearer(w.teacher().tokens()))).andExpect(status().isForbidden());

        CLAUDE.willRespondInSequence(List.of(
                FakeClaude.ok(FakeClaude.toolUseMessage("claude-opus-5-5", "toolu_1", "get_academic_summary", Map.of())),
                FakeClaude.ok(FakeClaude.toolUseMessage("claude-opus-5-5", "toolu_2", "search_resources",
                        Map.of("category", "CONSEJERIA"))),
                // Un recurso inventado se devuelve como error de herramienta y el agente corrige.
                FakeClaude.ok(FakeClaude.toolUseMessage("claude-opus-5-5", "toolu_3", "submit_draft",
                        draft("RECURSO-INVENTADO"))),
                FakeClaude.ok(FakeClaude.toolUseMessage("claude-opus-5-5", "toolu_4", "submit_draft",
                        draft("CONSEJERIA-ACADEMICA")))));
        relogin(counselor);
        JsonNode run = read(mvc.perform(post(path).header("Authorization", bearer(counselor.tokens())))
                .andExpect(status().isCreated()));

        assertThat(run.get("status").asText()).isEqualTo("BORRADOR");
        assertThat(run.get("requiresHumanReview").asBoolean()).isTrue();
        assertThat(run.at("/draft/actions/0/resourceId").asText()).isEqualTo("CONSEJERIA-ACADEMICA");
        assertThat(run.get("toolCalls").findValuesAsText("toolName"))
                .containsExactly("get_academic_summary", "search_resources", "submit_draft", "submit_draft");
        assertThat(run.get("toolCalls").get(2).get("error").asBoolean()).isTrue();
        Integer logged = jdbc.queryForObject("SELECT count(*) FROM agent_tool_calls WHERE run_id = ?", Integer.class,
                java.util.UUID.fromString(run.get("id").asText()));
        assertThat(logged).isEqualTo(4);

        // Las herramientas solo entregaron datos de la lista blanca y sin PII.
        List<JsonNode> payloads = CLAUDE.userPayloads();
        assertThat(payloads).isNotEmpty();
        for (JsonNode p : payloads) {
            assertThat(RiskEngineIT.paths(p)).allMatch(PayloadWhitelist.RISK_PAYLOAD::contains);
            assertThat(p.toString()).doesNotContain("Estudiante de Prueba", w.studentEmail());
        }
        JsonNode request = CLAUDE.requests().get(0);
        assertThat(request.get("tools").findValuesAsText("name")).containsExactly("get_academic_summary",
                "get_attendance", "get_risk_history", "search_resources", "submit_draft");

        JsonNode again = read(mvc.perform(get("/api/v1/retention-agent/drafts/" + run.get("id").asText())
                .header("Authorization", bearer(counselor.tokens()))).andExpect(status().isOk()));
        assertThat(again.get("status").asText()).isEqualTo("BORRADOR");
        Actor otherCounselor = staff(Set.of(Role.CONSEJERO), Set.of(MED));
        mvc.perform(get("/api/v1/retention-agent/drafts/" + run.get("id").asText())
                .header("Authorization", bearer(otherCounselor.tokens()))).andExpect(status().isForbidden());
    }

    @Test
    void providerFailuresEndTheRunWithoutADraft() throws Exception {
        AttendanceWorld w = highRiskWorld(ISW);
        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of(ISW));
        setting("ai.agent.enabled", true);
        CLAUDE.willRespond(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"x\"}}"));
        relogin(counselor);
        JsonNode run = read(mvc.perform(post("/api/v1/students/" + w.studentId() + "/retention-agent/drafts")
                .header("Authorization", bearer(counselor.tokens()))).andExpect(status().isCreated()));
        assertThat(run.get("status").asText()).isEqualTo("FALLIDO");
        assertThat(run.get("failureReason").asText()).isEqualTo("UNAVAILABLE");
        assertThat(run.get("draft").isNull()).isTrue();
    }
}
