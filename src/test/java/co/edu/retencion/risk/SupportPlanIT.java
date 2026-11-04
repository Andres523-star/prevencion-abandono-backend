package co.edu.retencion.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.support.AttendanceWorld;
import co.edu.retencion.support.AttendanceWorld.Actor;
import co.edu.retencion.support.FakeClaude;
import co.edu.retencion.support.RiskIntegrationTest;

/**
 * Plan de apoyo por asistencia: el umbral solo encola (la petición del docente no espera a la IA),
 * el worker analiza y propone el plan, y un humano lo aprueba. La IA nunca recibe el nombre.
 */
class SupportPlanIT extends RiskIntegrationTest {

    static final String PLAN_PROMPT = "Redacta un plan de apoyo";

    private void stubAi(String planJson) {
        CLAUDE.willReturnExplanation("claude-opus-5-5", FakeClaude.validExplanation("ALTO", "TUT-ACADEMICA"));
        CLAUDE.willRespondWhenBodyContains(PLAN_PROMPT,
                FakeClaude.ok(FakeClaude.textMessage("claude-opus-5-5", planJson, "end_turn")));
    }

    @Test
    void criticalThresholdProducesAnAiPlanThatAHumanApproves() throws Exception {
        AttendanceWorld w = world(ISW);
        stubAi(FakeClaude.validPlan("Hola {{nombre}}, queremos ayudarte a terminar {{materia}}."));
        for (int i = 0; i < 3; i++) {
            markAbsence(w, i);
        }
        // Tomar asistencia no llamó a la IA: todo quedó en la cola.
        assertThat(CLAUDE.requestCount()).isZero();
        assertThat(worker.drain(clock.instant().plus(Duration.ofHours(1)))).isGreaterThanOrEqualTo(2);

        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of(ISW));
        JsonNode plans = read(mvc.perform(get("/api/v1/students/" + w.studentId() + "/support-plans")
                .header("Authorization", bearer(counselor.tokens()))).andExpect(status().isOk()));
        JsonNode plan = plans.get(0);
        assertThat(plan.get("triggerLevel").asText()).isEqualTo("CRITICO");
        assertThat(plan.get("source").asText()).isEqualTo("AI");
        assertThat(plan.get("status").asText()).isEqualTo("PROPUESTO");
        assertThat(plan.get("requiresHumanApproval").asBoolean()).isTrue();
        assertThat(plan.get("studentMessage").asText()).contains("{{nombre}}");
        assertThat(plan.get("actions").findValuesAsText("type")).contains("CONTACTO_DOCENTE", "TUTORIA");
        // El plan de AVISO (por plantilla) quedó reemplazado por el del nivel crítico.
        assertThat(plans.findValuesAsText("status")).doesNotContain("APROBADO");
        Integer thresholdAnalyses = jdbc.queryForObject("SELECT count(*) FROM risk_assessments WHERE student_id = ? "
                + "AND trigger_type = 'THRESHOLD'", Integer.class, w.studentId());
        assertThat(thresholdAnalyses).isEqualTo(1);

        // La IA recibió la materia por referencia y el nivel, nunca el nombre.
        JsonNode planPayload = CLAUDE.userPayloads().stream().filter(p -> p.has("plan")).findFirst().orElseThrow();
        assertThat(planPayload.at("/plan/attendanceLevel").asText()).isEqualTo("CRITICO");
        assertThat(planPayload.at("/plan/subjectRef").asText()).isEqualTo("M1");
        assertThat(planPayload.toString()).doesNotContain("Estudiante de Prueba");

        // Ver el plan con el nombre es una lectura de dato personal: exige motivo y la rellena el backend.
        String id = plan.get("id").asText();
        mvc.perform(get("/api/v1/support-plans/" + id + "/rendered").header("Authorization", bearer(counselor.tokens())))
                .andExpect(status().isBadRequest());
        JsonNode rendered = read(mvc.perform(get("/api/v1/support-plans/" + id + "/rendered")
                .header("Authorization", bearer(counselor.tokens())).header("X-Access-Reason", "Contactar al estudiante"))
                .andExpect(status().isOk()));
        assertThat(rendered.get("studentMessage").asText())
                .isEqualTo("Hola Estudiante, queremos ayudarte a terminar Materia de prueba.");
        assertThat(rendered.get("actions").get(0).get("description").asText()).doesNotContain("{{");

        // Revisión humana: docente no aprueba, otro programa no lo ve, rechazo exige comentario.
        relogin(w.teacher());
        postJson("/api/v1/support-plans/" + id + "/approve", w.teacher().tokens(), Map.of())
                .andExpect(status().isForbidden());
        Actor otherCoordinator = staff(Set.of(Role.COORDINADOR), Set.of(MED));
        postJson("/api/v1/support-plans/" + id + "/approve", otherCoordinator.tokens(), Map.of())
                .andExpect(status().isNotFound());
        postJson("/api/v1/support-plans/" + id + "/reject", counselor.tokens(), Map.of("comment", " "))
                .andExpect(status().isBadRequest());
        JsonNode approved = read(postJson("/api/v1/support-plans/" + id + "/approve", counselor.tokens(),
                Map.of("comment", "Adelante")).andExpect(status().isOk()));
        assertThat(approved.get("status").asText()).isEqualTo("APROBADO");
        assertThat(approved.get("reviewedBy").asText()).isEqualTo(counselor.userId().toString());
        postJson("/api/v1/support-plans/" + id + "/approve", counselor.tokens(), Map.of()).andExpect(status().isConflict());
    }

    @Test
    void aPlanWithForbiddenPlaceholdersFallsBackToTheRulesTemplate() throws Exception {
        AttendanceWorld w = world(ISW);
        stubAi(FakeClaude.validPlan("Hola {{nombre}}, tu documento {{documento}}"));
        for (int i = 0; i < 3; i++) {
            markAbsence(w, i);
        }
        worker.drain(clock.instant().plus(Duration.ofHours(1)));
        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of(ISW));
        JsonNode plan = read(mvc.perform(get("/api/v1/students/" + w.studentId() + "/support-plans")
                .header("Authorization", bearer(counselor.tokens()))).andExpect(status().isOk())).get(0);
        assertThat(plan.get("triggerLevel").asText()).isEqualTo("CRITICO");
        assertThat(plan.get("source").asText()).isEqualTo("RULES");
        assertThat(plan.get("fallbackReason").asText()).isEqualTo("INVALID_OUTPUT");
        assertThat(plan.get("studentMessage").asText()).doesNotContain("documento");
        assertThat(plan.get("actions").findValuesAsText("type")).contains("CONTACTO_DOCENTE", "CONSEJERIA");
    }

    @Test
    void gradeAndAbsenceEventsAreDebouncedIntoASingleAnalysis() throws Exception {
        AttendanceWorld w = world(ISW);
        setting("ai.enabled", false);
        markAbsence(w, 0);
        for (int i = 0; i < 3; i++) {
            postJson("/api/v1/enrollments/" + w.enrollmentId() + "/grades", w.teacher().tokens(), Map.of(
                    "value", new java.math.BigDecimal("2.5"), "assessedOn", w.sessionDate(0).toString(),
                    "description", "Taller " + i)).andExpect(status().isCreated());
        }
        Integer queued = jdbc.queryForObject("SELECT count(*) FROM risk_work_queue WHERE student_id = ? AND kind = 'ANALYZE'",
                Integer.class, w.studentId());
        assertThat(queued).isEqualTo(1);
        // Antes del antirrebote no se procesa; después, un solo análisis.
        assertThat(worker.drain(clock.instant())).isZero();
        worker.drain(clock.instant().plus(Duration.ofMinutes(5)));
        assertThat(assessmentsOf(w.studentId())).isEqualTo(1);
        String trigger = jdbc.queryForObject("SELECT trigger_type FROM risk_assessments WHERE student_id = ?",
                String.class, w.studentId());
        assertThat(trigger).isEqualTo("EVENT");
    }
}
