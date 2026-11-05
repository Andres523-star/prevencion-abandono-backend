package co.edu.retencion.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.support.AnalyticsIntegrationTest;
import co.edu.retencion.support.AttendanceWorld;
import co.edu.retencion.support.AttendanceWorld.Actor;

/** Tablero: contenido según el rol, listas paginadas y auditoría. */
class DashboardIT extends AnalyticsIntegrationTest {

    private JsonNode summary(Actor actor, String size) throws Exception {
        return read(mvc.perform(get("/api/v1/dashboard/summary").param("size", size)
                .header("Authorization", bearer(actor.tokens()))).andExpect(status().isOk()));
    }

    @Test
    void adminSeesEverythingAndCoordinatorOnlyTheirProgram() throws Exception {
        dataset();
        refresh();
        Actor admin = admin();
        JsonNode global = summary(admin, "5");
        assertThat(global.get("scope").asText()).isEqualTo("GLOBAL");
        JsonNode totals = global.get("totals");
        assertThat(totals.get("students").asLong()).isEqualTo(count("SELECT count(DISTINCT s.id) FROM students s JOIN enrollments e "
                + "ON e.student_id = s.id WHERE s.status = 'ACTIVO' AND s.deleted_at IS NULL"));
        assertThat(totals.get("atRisk").asLong()).isEqualTo(totals.get("high").asLong() + totals.get("medium").asLong());
        assertThat(totals.get("atRisk").asLong()).isPositive();
        assertThat(totals.get("openAlerts").isNull()).isFalse();
        assertThat(totals.get("dropoutRate").decimalValue().signum()).isPositive();
        assertThat(totals.get("refreshedAt").asText()).isNotBlank();
        assertThat(global.get("lists").fieldNames()).toIterable().containsExactly("ALTO", "MEDIO", "BAJO");
        assertThat(global.at("/lists/ALTO/items")).hasSize(5);
        assertThat(global.at("/lists/ALTO/items/0/anonCode").asText()).startsWith("EST-");

        Actor coordinator = staff(Set.of(Role.COORDINADOR), Set.of(ISW));
        JsonNode program = summary(coordinator, "50");
        assertThat(program.get("scope").asText()).isEqualTo("PROGRAMS");
        assertThat(program.at("/totals/students").asLong()).isLessThan(totals.get("students").asLong());
        assertThat(program.at("/lists/ALTO/items").findValuesAsText("programId")).containsOnly(ISW.toString());
    }

    @Test
    void listsPageByKeysetAndSkipWithdrawnStudents() throws Exception {
        dataset();
        Actor coordinator = staff(Set.of(Role.COORDINADOR), Set.of(ISW));
        Set<String> seen = new HashSet<>();
        String after = null;
        int pages = 0;
        do {
            var request = get("/api/v1/dashboard/students").param("level", "MEDIO").param("size", "20")
                    .header("Authorization", bearer(coordinator.tokens()));
            if (after != null) {
                request.param("after", after);
            }
            JsonNode page = read(mvc.perform(request).andExpect(status().isOk()));
            assertThat(page.get("items").size()).isLessThanOrEqualTo(20);
            page.get("items").forEach(i -> assertThat(seen.add(i.get("studentId").asText())).as("sin repetidos").isTrue());
            after = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
            pages++;
        } while (after != null);
        assertThat(pages).isGreaterThan(1);
        assertThat((long) seen.size()).isEqualTo(count("SELECT count(*) FROM risk_student_state r JOIN students s ON s.id = r.student_id "
                + "WHERE r.last_level = 'MEDIO' AND s.status = 'ACTIVO' AND r.program_id = ?", ISW));
    }

    @Test
    void teacherSeesOnlyTheirGroupsAndNoProgramIndicators() throws Exception {
        AttendanceWorld w = highRiskWorld(ISW);
        setting("ai.enabled", false);
        analyze(staff(Set.of(Role.CONSEJERO), Set.of(ISW)), w.studentId());
        awaitListeners();
        relogin(w.teacher());
        JsonNode teacher = summary(w.teacher(), "10");
        assertThat(teacher.get("scope").asText()).isEqualTo("GROUPS");
        assertThat(teacher.at("/totals/students").asLong()).isEqualTo(1);
        assertThat(teacher.at("/totals/high").asLong()).isEqualTo(1);
        assertThat(teacher.at("/totals/openAlerts").isNull()).isTrue();
        assertThat(teacher.at("/totals/dropoutRate").isNull()).isTrue();
        assertThat(teacher.at("/lists/ALTO/items").findValuesAsText("studentId")).containsExactly(w.studentId().toString());
        assertThat(teacher.get("notifications").findValuesAsText("type")).contains("RIESGO_ESTUDIANTE");
        assertThat(teacher.toString()).doesNotContain("Estudiante de Prueba");

        Actor stranger = staff(Set.of(Role.DOCENTE), Set.of());
        JsonNode empty = summary(stranger, "10");
        assertThat(empty.at("/totals/students").asLong()).isZero();
        assertThat(empty.at("/lists/ALTO/items")).isEmpty();

        relogin(w.student());
        mvc.perform(get("/api/v1/dashboard/summary").header("Authorization", bearer(w.student().tokens())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/dashboard/summary")).andExpect(status().isUnauthorized());
        assertThat(count("SELECT count(*) FROM audit_log WHERE entity_type = 'Dashboard' AND actor_user_id = ?",
                w.teacher().userId())).isPositive();
    }

    @Test
    void counselorWithoutProgramsIsDenied() throws Exception {
        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of());
        mvc.perform(get("/api/v1/dashboard/summary").header("Authorization", bearer(counselor.tokens())))
                .andExpect(status().isForbidden());
        assertThat(Map.of()).isEmpty();
    }
}
