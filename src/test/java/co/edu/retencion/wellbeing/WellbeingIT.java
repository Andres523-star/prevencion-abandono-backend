package co.edu.retencion.wellbeing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.support.AttendanceWorld.Actor;
import co.edu.retencion.support.RecordsIntegrationTest;

/** Bienestar: solo PSICOLOGO, lectura con motivo y auditoría, contenido cifrado en la base. */
class WellbeingIT extends RecordsIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void onlyPsychologistsReadAndWriteAssessmentsWithAReason() throws Exception {
        Actor admin = staff(Set.of(Role.ADMIN), Set.of());
        Actor psychologist = staff(Set.of(Role.PSICOLOGO), Set.of());
        UUID student = createStudent(admin.tokens(), ISW, null);
        String path = "/api/v1/students/" + student + "/wellbeing/assessments";

        UUID assessment = UUID.fromString(read(postJson(path, psychologist.tokens(), Map.of("type", "PSICOLOGICA",
                "summary", "Ansiedad por carga académica", "findings", "Sueño irregular"))
                .andExpect(status().isCreated())).get("id").asText());

        // Cifrado en columna: el texto no está en claro en la base.
        String stored = jdbc.queryForObject("SELECT summary_ciphertext FROM wellbeing_assessments WHERE id = ?",
                String.class, assessment);
        assertThat(stored).startsWith("v1.").doesNotContain("Ansiedad");

        mvc.perform(get(path).header("Authorization", bearer(psychologist.tokens())))
                .andExpect(status().isBadRequest());
        mvc.perform(get(path).header("Authorization", bearer(psychologist.tokens()))
                        .header("X-Access-Reason", "Seguimiento de remisión"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].summary").value("Ansiedad por carga académica"));

        for (Set<Role> roles : java.util.List.of(Set.of(Role.ADMIN), Set.of(Role.CONSEJERO), Set.of(Role.COORDINADOR),
                Set.of(Role.DOCENTE))) {
            Actor other = roles.contains(Role.ADMIN) ? admin : staff(roles, Set.of(ISW));
            mvc.perform(get(path).header("Authorization", bearer(other.tokens()))
                    .header("X-Access-Reason", "curiosidad")).andExpect(status().isForbidden());
            postJson(path, other.tokens(), Map.of("type", "FISICA", "summary", "x")).andExpect(status().isForbidden());
        }

        Integer audited = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_type = 'WellbeingAssessment'"
                + " AND entity_id = ?", Integer.class, student.toString());
        assertThat(audited).isPositive();
    }

    @Test
    void referralsFollowTheirLifecycle() throws Exception {
        Actor admin = staff(Set.of(Role.ADMIN), Set.of());
        Actor psychologist = staff(Set.of(Role.PSICOLOGO), Set.of());
        UUID student = createStudent(admin.tokens(), ISW, null);
        UUID referral = UUID.fromString(read(postJson("/api/v1/students/" + student + "/wellbeing/referrals",
                psychologist.tokens(), Map.of("service", "TRABAJO_SOCIAL", "reason", "Apoyo económico"))
                .andExpect(status().isCreated())).get("id").asText());

        String move = "/api/v1/wellbeing/referrals/" + referral + "/status";
        postJson(move, psychologist.tokens(), Map.of("status", "ATENDIDA")).andExpect(status().isOk());
        postJson(move, psychologist.tokens(), Map.of("status", "CERRADA")).andExpect(status().isOk())
                .andExpect(jsonPath("$.closedAt").isNotEmpty());
        postJson(move, psychologist.tokens(), Map.of("status", "ABIERTA")).andExpect(status().isConflict());
    }
}
