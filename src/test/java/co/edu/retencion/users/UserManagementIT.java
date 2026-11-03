package co.edu.retencion.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.support.IntegrationTest;

/**
 * Gestión de usuarios: alta previa al primer ingreso (vínculo automático al entrar por
 * Supabase), coordinador con su programa, edición, listado por rol y borrado lógico.
 */
class UserManagementIT extends IntegrationTest {

    private Tokens admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = loginAs(Set.of(Role.ADMIN), Set.of());
    }

    @Test
    void preProvisionedCoordinatorIsLinkedOnFirstLogin() throws Exception {
        String sub = UUID.randomUUID().toString();
        String email = "coordinacion-" + sub.substring(0, 8) + "@universidad.edu.co";
        JsonNode created = read(postJson("/api/v1/users", admin, Map.of("email", email,
                "displayName", "Coordinación ISW", "roles", List.of("COORDINADOR"), "programIds", List.of(ISW)))
                .andExpect(status().isCreated()));
        UUID userId = UUID.fromString(created.get("id").asText());

        Tokens session = loginWithSupabase(sub, email, "aal2");
        assertThat(session.userId()).isEqualTo(userId);
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(session)))
                .andExpect(jsonPath("$.roles[0]").value("COORDINADOR"))
                .andExpect(jsonPath("$.programIds[0]").value(ISW.toString()));
        postJson("/api/v1/users", admin, Map.of("email", email.toUpperCase(), "displayName", "Otra",
                "roles", List.of(), "programIds", List.of())).andExpect(status().isConflict());
    }

    @Test
    void programScopedRolesRequireExistingProgram() throws Exception {
        postJson("/api/v1/users", admin, Map.of("email", "x-" + UUID.randomUUID() + "@universidad.edu.co",
                "displayName", "Sin programa", "roles", List.of("CONSEJERO"), "programIds", List.of()))
                .andExpect(status().isBadRequest());
        postJson("/api/v1/users", admin, Map.of("email", "y-" + UUID.randomUUID() + "@universidad.edu.co",
                "displayName", "Programa falso", "roles", List.of("COORDINADOR"), "programIds", List.of(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void editListByRoleAndSoftDelete() throws Exception {
        String email = "docente-" + UUID.randomUUID().toString().substring(0, 8) + "@universidad.edu.co";
        UUID teacher = UUID.fromString(read(postJson("/api/v1/users", admin, Map.of("email", email,
                "displayName", "Docente", "roles", List.of("DOCENTE"), "programIds", List.of()))).get("id").asText());

        mvc.perform(patch("/api/v1/users/" + teacher).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Docente Renombrado\"}"))
                .andExpect(jsonPath("$.displayName").value("Docente Renombrado"));

        JsonNode teachers = read(mvc.perform(get("/api/v1/users").param("role", "DOCENTE").param("size", "200")
                .header("Authorization", bearer(admin))));
        assertThat(teachers.get("items").findValuesAsText("id")).contains(teacher.toString());
        assertThat(teachers.get("items").findValues("roles")).allSatisfy(r -> assertThat(r.toString()).contains("DOCENTE"));

        mvc.perform(delete("/api/v1/users/" + teacher).header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/users/" + teacher).header("Authorization", bearer(admin))
                .header("X-Access-Reason", "verificación")).andExpect(status().isForbidden());
        // La persona eliminada ya no puede entrar con ese correo.
        exchange(supabaseToken(UUID.randomUUID().toString(), email, "aal1")).andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/users/" + admin.userId()).header("Authorization", bearer(admin)))
                .andExpect(status().isConflict());
    }

    @Test
    void nonAdminCannotManageUsers() throws Exception {
        Tokens coordinator = loginAs(Set.of(Role.COORDINADOR), Set.of(ISW));
        mvc.perform(post("/api/v1/users").header("Authorization", bearer(coordinator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"z@u.co\",\"displayName\":\"z\",\"roles\":[],\"programIds\":[]}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/users/" + admin.userId()).header("Authorization", bearer(coordinator)))
                .andExpect(status().isForbidden());
    }
}
