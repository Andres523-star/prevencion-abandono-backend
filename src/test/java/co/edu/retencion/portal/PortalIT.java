package co.edu.retencion.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.support.AttendanceWorld;
import co.edu.retencion.support.AttendanceWorld.Actor;
import co.edu.retencion.support.CampusIntegrationTest;

/** Portal del estudiante: sus datos, sin etiquetas de riesgo, y la apelación de una justificación. */
class PortalIT extends CampusIntegrationTest {

    private JsonNode portal(Actor actor, String path) throws Exception {
        relogin(actor);
        return read(mvc.perform(get("/api/v1/portal/" + path).header("Authorization", bearer(actor.tokens())))
                .andExpect(status().isOk()));
    }

    @Test
    void overviewAndAbsencesShowOnlyTheStudentsOwnData() throws Exception {
        AttendanceWorld w = world(ISW);
        markAbsence(w, 0);
        markAbsence(w, 1);
        postJson("/api/v1/enrollments/" + w.enrollmentId() + "/grades", w.teacher().tokens(), Map.of(
                "value", new java.math.BigDecimal("2.5"), "assessedOn", w.sessionDate(1).toString(), "description", "Quiz"))
                .andExpect(status().isCreated());
        putLink(w.teacher(), "https://calendar.google.com/calendar/appointments/schedules/portal");

        JsonNode overview = portal(w.student(), "overview");
        assertThat(overview.get("anonCode").asText()).startsWith("EST-");
        JsonNode course = overview.get("courses").get(0);
        assertThat(course.get("subjectName").asText()).isEqualTo("Materia de prueba");
        assertThat(course.at("/absences/counted").asInt()).isEqualTo(2);
        assertThat(course.at("/absences/remainingBeforeLimit").asInt()).isEqualTo(2);
        assertThat(course.at("/absences/state").asText()).isEqualTo("Tienes 2 faltas; el límite es 4");
        assertThat(course.at("/rule/maxAbsences").asInt()).isEqualTo(4);
        assertThat(course.at("/rule/countsPerSession").asBoolean()).isTrue();
        assertThat(course.get("appointmentLink").asText()).contains("calendar.google.com");
        assertThat(course.get("grades")).hasSize(1);
        assertThat(course.get("average").decimalValue()).isEqualByComparingTo("2.5");

        JsonNode absences = portal(w.student(), "absences");
        assertThat(absences).hasSize(2);
        assertThat(absences.get(0).get("type").asText()).isEqualTo("FALTA");
        assertThat(absences.get(0).get("justification").asText()).isEqualTo("NINGUNA");
        assertThat(absences.get(0).get("justifyBefore").asText()).isNotBlank();

        // Lo que ve el estudiante no lleva lenguaje de riesgo ni datos de otros.
        String text = (overview.toString() + absences).toLowerCase();
        assertThat(text).doesNotContain("riesgo").doesNotContain("alto").doesNotContain("probab").doesNotContain("score");

        relogin(w.teacher());
        for (String path : new String[] {"overview", "absences", "support"}) {
            mvc.perform(get("/api/v1/portal/" + path).header("Authorization", bearer(w.teacher().tokens())))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void supportShowsOnlyHumanApprovedPlansInCaringLanguage() throws Exception {
        AttendanceWorld w = highRiskWorld(ISW);
        setting("ai.enabled", false);
        JsonNode before = portal(w.student(), "support");
        assertThat(before.get("plans")).isEmpty();
        assertThat(before.get("services")).isNotEmpty();

        awaitListeners();
        worker.drain(clock.instant().plus(Duration.ofHours(1)));
        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of(ISW));
        JsonNode plans = read(mvc.perform(get("/api/v1/students/" + w.studentId() + "/support-plans")
                .header("Authorization", bearer(counselor.tokens()))).andExpect(status().isOk()));
        assertThat(plans).isNotEmpty();
        assertThat(portal(w.student(), "support").get("plans")).as("propuesto: aún no lo ve").isEmpty();

        relogin(counselor);
        postJson("/api/v1/support-plans/" + plans.get(0).get("id").asText() + "/approve", counselor.tokens(),
                Map.of("comment", "Revisado")).andExpect(status().isOk());
        JsonNode support = portal(w.student(), "support");
        assertThat(support.get("plans")).hasSize(1);
        String message = support.at("/plans/0/message").asText();
        assertThat(message).isNotBlank().doesNotContain("{{");
        String all = support.toString().toLowerCase();
        assertThat(all).doesNotContain("riesgo").doesNotContain("\"alto\"").doesNotContain("crítico").doesNotContain("critico")
                .doesNotContain("probabilidad").doesNotContain("%").doesNotContain("score").doesNotContain("level");
    }

    @Test
    void aRejectedJustificationCanBeAppealedAndTheTeacherDecidesAgain() throws Exception {
        AttendanceWorld w = world(ISW);
        UUID record = markAbsence(w, 0);
        relogin(w.student());
        String id = read(postJson("/api/v1/justifications", w.student().tokens(), Map.of("attendanceRecordId", record,
                "category", "SALUD", "description", "Cita médica")).andExpect(status().isCreated())).get("id").asText();
        relogin(w.teacher());
        decide(w.teacher(), id, false, "Falta el soporte").andExpect(status().isOk());
        assertThat(counter(w).get("unjustified").asInt()).isEqualTo(1);

        Actor stranger = staff(Set.of(Role.DOCENTE), Set.of());
        postJson("/api/v1/justifications/" + id + "/appeal", stranger.tokens(), Map.of("text", "x")).andExpect(status().isForbidden());
        relogin(w.student());
        JsonNode appealed = read(postJson("/api/v1/justifications/" + id + "/appeal", w.student().tokens(),
                Map.of("text", "Adjunto la incapacidad médica")).andExpect(status().isOk()));
        assertThat(appealed.get("status").asText()).isEqualTo("APELADA");
        assertThat(counter(w).get("pending").asInt()).as("vuelve a quedar pendiente").isEqualTo(1);
        postJson("/api/v1/justifications/" + id + "/appeal", w.student().tokens(), Map.of("text", "otra"))
                .andExpect(status().isConflict());
        assertThat(notifications(w.teacher()).findValuesAsText("type")).contains("JUSTIFICACION_APELADA");

        relogin(w.teacher());
        JsonNode pending = read(mvc.perform(get("/api/v1/justifications/pending")
                .header("Authorization", bearer(w.teacher().tokens()))).andExpect(status().isOk()));
        assertThat(pending.findValuesAsText("id")).contains(id);
        JsonNode decision = read(decide(w.teacher(), id, true, null).andExpect(status().isOk()));
        assertThat(decision.at("/counter/countedAbsences").asInt()).isZero();

        relogin(w.student());
        postJson("/api/v1/justifications/" + id + "/appeal", w.student().tokens(), Map.of("text", "x"))
                .andExpect(status().isConflict());
        assertThat(notifications(w.student()).findValuesAsText("type")).contains("JUSTIFICACION_DECIDIDA");
    }

    @Test
    void appealsExpire() throws Exception {
        AttendanceWorld w = world(ISW);
        UUID record = markAbsence(w, 0);
        relogin(w.student());
        String id = read(postJson("/api/v1/justifications", w.student().tokens(), Map.of("attendanceRecordId", record,
                "category", "OTRO", "description", "Transporte")).andExpect(status().isCreated())).get("id").asText();
        relogin(w.teacher());
        decide(w.teacher(), id, false, "No aplica").andExpect(status().isOk());
        clock.setNow(clock.instant().plus(Duration.ofDays(6)));
        relogin(w.student());
        postJson("/api/v1/justifications/" + id + "/appeal", w.student().tokens(), Map.of("text", "Tarde"))
                .andExpect(status().isConflict());
    }

    private org.springframework.test.web.servlet.ResultActions decide(Actor teacher, String id, boolean approved,
                                                                      String comment) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("approved", approved);
        body.put("comment", comment);
        return postJson("/api/v1/justifications/" + id + "/decision", teacher.tokens(), body);
    }

    private void putLink(Actor teacher, String url) throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/users/me/appointment-link")
                        .header("Authorization", bearer(teacher.tokens()))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("url", url))))
                .andExpect(status().isOk());
    }
}
