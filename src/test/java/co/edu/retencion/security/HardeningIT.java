package co.edu.retencion.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.support.AttendanceWorld;
import co.edu.retencion.support.AttendanceWorld.Actor;
import co.edu.retencion.support.CampusIntegrationTest;

/** Límites de tamaño, intentos de inyección y respuestas que no revelan existencia. */
class HardeningIT extends CampusIntegrationTest {

    private static final String[] PAYLOADS = {"' OR '1'='1", "1; DROP TABLE students;--", "x' UNION SELECT * FROM users--",
            "<script>alert(1)</script>", "../../etc/passwd", "${jndi:ldap://x/a}"};

    @Test
    void oversizedJsonBodiesAreRejectedBeforeParsing() throws Exception {
        Actor admin = staff(Set.of(Role.ADMIN), Set.of());
        String huge = "{\"comment\":\"" + "a".repeat(2 * 1024 * 1024) + "\"}";
        mvc.perform(post("/api/v1/justifications/expire").header("Authorization", bearer(admin.tokens()))
                .contentType(MediaType.APPLICATION_JSON).content(huge)).andExpect(status().isPayloadTooLarge());
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(huge))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void injectionAttemptsAreTreatedAsData() throws Exception {
        Actor admin = staff(Set.of(Role.ADMIN), Set.of());
        Actor coordinator = staff(Set.of(Role.COORDINADOR), Set.of(ISW));
        long students = jdbc.queryForObject("SELECT count(*) FROM students", Long.class);
        for (String payload : PAYLOADS) {
            int setting = mvc.perform(get("/api/v1/settings/{key}", payload).header("Authorization", bearer(admin.tokens())))
                    .andReturn().getResponse().getStatus();
            assertThat(setting).as("settings con %s", payload).isBetween(400, 499);
            int search = mvc.perform(get("/api/v1/students/search").param("document", payload)
                    .header("Authorization", bearer(coordinator.tokens()))).andReturn().getResponse().getStatus();
            assertThat(search).as("búsqueda con %s", payload).isBetween(400, 499);
            int chart = mvc.perform(get("/api/v1/analytics/by-semester").param("programId", payload)
                    .header("Authorization", bearer(admin.tokens()))).andReturn().getResponse().getStatus();
            assertThat(chart).as("filtro con %s", payload).isEqualTo(400);
            int trend = mvc.perform(get("/api/v1/analytics/trend").param("granularity", payload)
                    .header("Authorization", bearer(admin.tokens()))).andReturn().getResponse().getStatus();
            assertThat(trend).isEqualTo(400);
        }
        AttendanceWorld w = world(ISW);
        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of(ISW));
        String caseId = read(postJson("/api/v1/retention-cases", counselor.tokens(), Map.of("studentId", w.studentId()))
                .andExpect(status().isCreated())).get("id").asText();
        postJson("/api/v1/retention-cases/" + caseId + "/close", counselor.tokens(), Map.of("outcome", "RETIRADO",
                "causeCode", "x'; DROP TABLE students;--", "exitSurvey", Map.of("q", "<script>")))
                .andExpect(status().isBadRequest());
        // Un texto con HTML se guarda como dato y vuelve igual (escaparlo es tarea de la interfaz).
        relogin(w.student());
        String saved = read(postJson("/api/v1/students/me/retention-requests", w.student().tokens(),
                Map.of("message", "<img src=x onerror=alert(1)>")).andExpect(status().is2xxSuccessful())).toString();
        assertThat(saved).doesNotContain("onerror");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM students", Long.class)).isGreaterThanOrEqualTo(students);
        assertThat(jdbc.queryForObject("SELECT to_regclass('students') IS NOT NULL", Boolean.class)).isTrue();
    }

    @Test
    void resourcesOutOfScopeAnswerAsIfTheyDidNotExist() throws Exception {
        AttendanceWorld w = world(ISW);
        Actor otherProgram = staff(Set.of(Role.COORDINADOR), Set.of(MED));
        int byDocument = mvc.perform(get("/api/v1/students/search").param("document", "1020304050")
                .header("Authorization", bearer(otherProgram.tokens()))).andReturn().getResponse().getStatus();
        int unknown = mvc.perform(get("/api/v1/students/search").param("document", "9999999999999")
                .header("Authorization", bearer(otherProgram.tokens()))).andReturn().getResponse().getStatus();
        assertThat(byDocument).isEqualTo(unknown);
        mvc.perform(get("/api/v1/reports/" + java.util.UUID.randomUUID()).header("Authorization", bearer(otherProgram.tokens())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/retention-cases/" + java.util.UUID.randomUUID())
                .header("Authorization", bearer(otherProgram.tokens()))).andExpect(status().isNotFound());
        assertThat(w.studentId()).isNotNull();
    }
}
