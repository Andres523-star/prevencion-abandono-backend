package co.edu.retencion.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.support.AttendanceWorld;
import co.edu.retencion.support.AttendanceWorld.Actor;
import co.edu.retencion.support.CampusIntegrationTest;

/** Casos de retención: etapas válidas/inválidas, seguimiento, cierre, autorreporte, embudo y reingreso. */
class RetentionIT extends CampusIntegrationTest {

    private JsonNode advance(Actor actor, String caseId, String stage) throws Exception {
        return read(postJson("/api/v1/retention-cases/" + caseId + "/advance", actor.tokens(), Map.of("stage", stage)));
    }

    @Test
    void caseLifecycleWithValidAndInvalidTransitions() throws Exception {
        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of(ISW));
        Actor coordinator = staff(Set.of(Role.COORDINADOR), Set.of(ISW));
        Actor psychologist = staff(Set.of(Role.PSICOLOGO), Set.of());
        Actor admin = staff(Set.of(Role.ADMIN), Set.of());
        UUID student = createStudent(admin.tokens(), ISW, null);

        JsonNode opened = read(postJson("/api/v1/retention-cases", counselor.tokens(), Map.of("studentId", student))
                .andExpect(status().isCreated()));
        String id = opened.get("id").asText();
        assertThat(opened.get("stage").asText()).isEqualTo("DETECTADO");
        assertThat(opened.get("origin").asText()).isEqualTo("CONSEJERO");
        assertThat(opened.get("assignedCounselorId").asText()).isEqualTo(counselor.userId().toString());
        postJson("/api/v1/retention-cases", counselor.tokens(), Map.of("studentId", student)).andExpect(status().isConflict());

        // Saltos y requisitos.
        postJson("/api/v1/retention-cases/" + id + "/advance", counselor.tokens(), Map.of("stage", "ENTREVISTA"))
                .andExpect(status().isConflict());
        postJson("/api/v1/retention-cases/" + id + "/advance", counselor.tokens(), Map.of("stage", "CONTACTADO"))
                .andExpect(status().isConflict());
        postJson("/api/v1/retention-cases/" + id + "/contacts", counselor.tokens(), Map.of("channel", "TELEFONO",
                "result", "NO_CONTESTA")).andExpect(status().isCreated());
        postJson("/api/v1/retention-cases/" + id + "/contacts", counselor.tokens(), Map.of("channel", "CHAT",
                "result", "CONTACTADO", "notes", "Acepta reunirse")).andExpect(status().isCreated());
        assertThat(advance(counselor, id, "CONTACTADO").get("contactedAt").asText()).isNotBlank();
        postJson("/api/v1/retention-cases/" + id + "/advance", counselor.tokens(), Map.of("stage", "DETECTADO"))
                .andExpect(status().isConflict());
        advance(counselor, id, "ENTREVISTA");

        // Notas de entrevista restringidas: consejero asignado y bienestar; no la coordinación.
        postJson("/api/v1/retention-cases/" + id + "/interview-notes", counselor.tokens(),
                Map.of("text", "Trabaja de noche y le cuesta asistir")).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/retention-cases/" + id + "/interview-notes")
                .header("Authorization", bearer(coordinator.tokens())).header("X-Access-Reason", "Seguimiento"))
                .andExpect(status().isForbidden());
        assertThat(read(mvc.perform(get("/api/v1/retention-cases/" + id + "/interview-notes")
                .header("Authorization", bearer(psychologist.tokens())).header("X-Access-Reason", "Acompañamiento"))
                .andExpect(status().isOk()))).hasSize(1);

        advance(counselor, id, "PLAN_DE_APOYO");
        postJson("/api/v1/retention-cases/" + id + "/advance", counselor.tokens(), Map.of("stage", "SEGUIMIENTO"))
                .andExpect(status().isConflict());
        String today = businessToday().toString();
        postJson("/api/v1/retention-cases/" + id + "/tasks", counselor.tokens(), Map.of("title", "Ajustar horario",
                "dueDate", today)).andExpect(status().isCreated());
        JsonNode following = advance(counselor, id, "SEGUIMIENTO");
        assertThat(following.get("followUps").findValuesAsText("offsetDays")).containsExactly("7", "15", "30");
        postJson("/api/v1/retention-cases/" + id + "/close", counselor.tokens(), Map.of("outcome", "RETIRADO"))
                .andExpect(status().isBadRequest());

        // A los 8 días se recuerda el seguimiento de 7 días (una sola vez).
        clock.setNow(clock.instant().plus(Duration.ofDays(8)));
        relogin(admin);
        assertThat(json.readTree(postJson("/api/v1/retention/follow-ups/remind", admin.tokens(), Map.of())
                .andReturn().getResponse().getContentAsString()).asInt()).isPositive();
        assertThat(json.readTree(postJson("/api/v1/retention/follow-ups/remind", admin.tokens(), Map.of())
                .andReturn().getResponse().getContentAsString()).asInt()).isZero();
        assertThat(notifications(counselor).findValuesAsText("type")).contains("SEGUIMIENTO_RETENCION");

        relogin(counselor);
        JsonNode closed = read(postJson("/api/v1/retention-cases/" + id + "/close", counselor.tokens(),
                Map.of("outcome", "RETENIDO")).andExpect(status().isOk()));
        assertThat(closed.get("stage").asText()).isEqualTo("CERRADO");
        assertThat(closed.get("followUps").findValuesAsText("status")).doesNotContain("PENDIENTE");
        postJson("/api/v1/retention-cases/" + id + "/advance", counselor.tokens(), Map.of("stage", "SEGUIMIENTO"))
                .andExpect(status().isConflict());
    }

    @Test
    void selfReportWithdrawalFunnelAndReentryCampaign() throws Exception {
        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of(ISW));
        Actor coordinator = staff(Set.of(Role.COORDINADOR), Set.of(ISW));
        onlyStaff(counselor, coordinator);
        AttendanceWorld w = world(ISW);

        relogin(w.student());
        JsonNode self = read(postJson("/api/v1/students/me/retention-requests", w.student().tokens(),
                Map.of("message", "Estoy pensando en retirarme porque no me alcanza el tiempo")).andExpect(status().isCreated()));
        assertThat(self.get("origin").asText()).isEqualTo("AUTORREPORTE");
        assertThat(self.get("assignedCounselorId").isNull()).as("el estudiante no ve datos internos").isTrue();
        String id = self.get("id").asText();
        relogin(counselor);
        assertThat(read(mvc.perform(get("/api/v1/retention-cases/" + id).header("Authorization", bearer(counselor.tokens())))
                .andExpect(status().isOk())).get("assignedCounselorId").asText()).isEqualTo(counselor.userId().toString());

        postJson("/api/v1/retention-cases/" + id + "/close", counselor.tokens(), Map.of("outcome", "RETIRADO",
                "causeCode", "NECESIDAD_DE_TRABAJAR")).andExpect(status().isBadRequest());
        postJson("/api/v1/retention-cases/" + id + "/close", counselor.tokens(), Map.of("outcome", "RETIRADO",
                "causeCode", "NO_EXISTE", "exitSurvey", Map.of("volveria", "sí"))).andExpect(status().isBadRequest());
        postJson("/api/v1/retention-cases/" + id + "/close", counselor.tokens(), Map.of("outcome", "RETIRADO",
                "causeCode", "NECESIDAD_DE_TRABAJAR", "exitSurvey", Map.of("volveria", "sí", "motivo", "trabajo")))
                .andExpect(status().isOk());
        String studentStatus = jdbc.queryForObject("SELECT status FROM students WHERE id = ?", String.class, w.studentId());
        assertThat(studentStatus).isEqualTo("RETIRADO");
        Integer cause = jdbc.queryForObject("SELECT count(*) FROM student_causes WHERE student_id = ? "
                + "AND cause_code = 'NECESIDAD_DE_TRABAJAR'", Integer.class, w.studentId());
        assertThat(cause).isEqualTo(1);
        Integer audited = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'RETENTION_CASE_CLOSE' "
                + "AND entity_id = ?", Integer.class, id);
        assertThat(audited).isPositive();

        JsonNode funnel = read(mvc.perform(get("/api/v1/retention/funnel").param("programId", ISW.toString())
                .header("Authorization", bearer(counselor.tokens()))).andExpect(status().isOk()));
        assertThat(funnel.get("detected").asLong()).isPositive();
        assertThat(funnel.get("withdrawn").asLong()).isPositive();
        mvc.perform(get("/api/v1/retention/funnel").param("programId", MED.toString())
                .header("Authorization", bearer(counselor.tokens()))).andExpect(status().isForbidden());

        // Reingreso: borrador que solo crea contactos al aprobarse.
        setting("retention.reentry-after-days", 0);
        clock.setNow(clock.instant().plusSeconds(60));
        relogin(coordinator);
        JsonNode campaign = read(postJson("/api/v1/retention/campaigns", coordinator.tokens(), Map.of())
                .andExpect(status().isCreated()));
        assertThat(campaign.get("status").asText()).isEqualTo("BORRADOR");
        assertThat(campaign.toString()).contains(id);
        assertThat(notifications(counselor).findValuesAsText("type")).doesNotContain("CAMPANA_REINGRESO");
        relogin(coordinator);
        postJson("/api/v1/retention/campaigns/" + campaign.get("id").asText() + "/approve", coordinator.tokens(), Map.of())
                .andExpect(status().isOk());
        assertThat(notifications(counselor).findValuesAsText("type")).contains("CAMPANA_REINGRESO");
    }

    @Test
    void highRiskOpensACaseAutomatically() throws Exception {
        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of(ISW));
        onlyStaff(counselor);
        AttendanceWorld w = highRiskWorld(ISW);
        setting("ai.enabled", false);
        assertThat(analyze(counselor, w.studentId()).get("level").asText()).isEqualTo("ALTO");
        awaitListeners();
        JsonNode cases = read(mvc.perform(get("/api/v1/students/" + w.studentId() + "/retention-cases")
                .header("Authorization", bearer(counselor.tokens()))).andExpect(status().isOk()));
        assertThat(cases).hasSize(1);
        assertThat(cases.get(0).get("origin").asText()).isEqualTo("AUTOMATICO");
        assertThat(cases.get(0).get("assignedCounselorId").asText()).isEqualTo(counselor.userId().toString());
        // Un segundo análisis ALTO no abre otro caso.
        analyze(counselor, w.studentId());
        awaitListeners();
        assertThat(read(mvc.perform(get("/api/v1/students/" + w.studentId() + "/retention-cases")
                .header("Authorization", bearer(counselor.tokens()))))).hasSize(1);
    }
}
