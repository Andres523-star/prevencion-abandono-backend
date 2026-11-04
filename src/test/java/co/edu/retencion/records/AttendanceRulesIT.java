package co.edu.retencion.records;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.support.AttendanceWorld;
import co.edu.retencion.support.AttendanceWorld.Actor;
import co.edu.retencion.support.RecordsIntegrationTest;

/** Reglas de asistencia y notas: falta por sesión, ventana de edición, políticas por programa, ABAC. */
class AttendanceRulesIT extends RecordsIntegrationTest {

    @Test
    void aFourHourSessionCountsAsASingleAbsence() throws Exception {
        AttendanceWorld w = world(ISW);
        JsonNode session = w.session(0);
        assertThat(Duration.between(java.time.LocalTime.parse(session.get("start").asText()),
                java.time.LocalTime.parse(session.get("end").asText()))).isEqualTo(Duration.ofHours(4));

        markAbsence(w, 0);
        JsonNode counter = counter(w);
        assertThat(counter.get("countedAbsences").asInt()).isEqualTo(1);
        assertThat(counter.get("unjustified").asInt()).isEqualTo(1);

        // Volver a guardar la misma falta no la duplica.
        mark(w.teacher().tokens(), w.sessionId(0), w.enrollmentId(), "FALTA", null).andExpect(status().isOk());
        assertThat(counter(w).get("countedAbsences").asInt()).isEqualTo(1);
    }

    @Test
    void eachProgramAppliesItsOwnPolicy() throws Exception {
        Actor admin = staff(Set.of(Role.ADMIN), Set.of());
        mvc.perform(put("/api/v1/programs/" + IIN + "/attendance-policy")
                        .header("Authorization", bearer(admin.tokens()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("maxAbsences", 3, "warningThreshold", 1,
                                "criticalThreshold", 2, "justificationWindowBusinessDays", 2))))
                .andExpect(status().isOk());

        AttendanceWorld strict = world(IIN);
        markAbsence(strict, 0);
        JsonNode strictCounter = counter(strict);
        assertThat(strictCounter.get("maxAbsences").asInt()).isEqualTo(3);
        assertThat(strictCounter.get("level").asText()).isEqualTo("AVISO");
        markAbsence(strict, 1);
        assertThat(counter(strict).get("level").asText()).isEqualTo("CRITICO");
        markAbsence(strict, 2);
        assertThat(counter(strict).get("level").asText()).isEqualTo("PERDIDA");

        AttendanceWorld standard = world(ISW);
        markAbsence(standard, 0);
        JsonNode standardCounter = counter(standard);
        assertThat(standardCounter.get("maxAbsences").asInt()).isEqualTo(4);
        assertThat(standardCounter.get("level").asText()).isEqualTo("NINGUNO");
    }

    @Test
    void afterTheEditWindowOnlyCoordinationOrAdminCorrectWithAReason() throws Exception {
        AttendanceWorld w = world(ISW);
        Actor coordinator = staff(Set.of(Role.COORDINADOR), Set.of(ISW));
        at(w.sessionEnd(0).plus(Duration.ofHours(49)), w, coordinator);

        mark(w.teacher().tokens(), w.sessionId(0), w.enrollmentId(), "FALTA", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("attendance_edit_window_closed"));
        mark(coordinator.tokens(), w.sessionId(0), w.enrollmentId(), "FALTA", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("attendance_correction_reason_required"));
        mark(coordinator.tokens(), w.sessionId(0), w.enrollmentId(), "FALTA", "Acta de corrección 12")
                .andExpect(status().isOk());
        assertThat(counter(w).get("countedAbsences").asInt()).isEqualTo(1);
    }

    @Test
    void attendanceIsLimitedToTheTeacherOfTheGroupAndToPastSessions() throws Exception {
        AttendanceWorld w = world(ISW);
        Actor other = staff(Set.of(Role.DOCENTE), Set.of());
        at(w.sessionEnd(0).plusSeconds(3600), w, other);

        mark(other.tokens(), w.sessionId(0), w.enrollmentId(), "FALTA", null).andExpect(status().isForbidden());
        mark(w.student().tokens(), w.sessionId(0), w.enrollmentId(), "PRESENTE", null).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/students/" + w.studentId() + "/absence-counters")
                .header("Authorization", bearer(other.tokens()))).andExpect(status().isForbidden());
    }

    @Test
    void gradesProduceRiskIndicatorsWithinScope() throws Exception {
        AttendanceWorld w = world(ISW);
        Actor other = staff(Set.of(Role.DOCENTE), Set.of());
        List<Map<String, Object>> grades = List.of(
                Map.of("enrollmentId", w.enrollmentId(), "value", 3.5, "assessedOn", w.sessionDate(0).toString(),
                        "description", "Quiz 1"),
                Map.of("enrollmentId", w.enrollmentId(), "value", 2.8, "assessedOn", w.sessionDate(1).toString(),
                        "description", "Parcial 1"),
                Map.of("enrollmentId", w.enrollmentId(), "value", 2.2, "assessedOn", w.sessionDate(2).toString(),
                        "description", "Quiz 2"));
        postJson("/api/v1/groups/" + w.groupId() + "/grades", w.teacher().tokens(), Map.of("grades", grades))
                .andExpect(status().is2xxSuccessful());
        postJson("/api/v1/groups/" + w.groupId() + "/grades", other.tokens(), Map.of("grades", grades))
                .andExpect(status().isForbidden());
        postJson("/api/v1/enrollments/" + w.enrollmentId() + "/grades", w.teacher().tokens(),
                Map.of("value", 6.0, "assessedOn", w.sessionDate(0).toString())).andExpect(status().isBadRequest());

        JsonNode indicators = read(mvc.perform(get("/api/v1/students/" + w.studentId() + "/grade-indicators")
                .header("Authorization", bearer(w.teacher().tokens()))).andExpect(status().isOk()));
        String text = indicators.toString();
        assertThat(text).contains("BAJA").contains("2.8");
        mvc.perform(get("/api/v1/students/" + w.studentId() + "/grade-indicators")
                .header("Authorization", bearer(other.tokens()))).andExpect(status().isForbidden());
    }
}
