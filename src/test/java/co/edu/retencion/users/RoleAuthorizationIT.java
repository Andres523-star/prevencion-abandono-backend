package co.edu.retencion.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.support.IntegrationTest;
import co.edu.retencion.support.TestSecrets;

/**
 * Autorización en dos niveles: RBAC por rol y ABAC por alcance (programa / titular), más la
 * auditoría de lecturas sensibles y accesos denegados.
 */
class RoleAuthorizationIT extends IntegrationTest {

    private static final String REASON = "X-Access-Reason";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void anonymousRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/v1/users")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"));
    }

    @Test
    void onlyAdminListsUsers() throws Exception {
        Tokens admin = loginAs(Set.of(Role.ADMIN), Set.of());
        Tokens student = loginAs(Set.of(Role.ESTUDIANTE), Set.of());
        Tokens teacher = loginAs(Set.of(Role.DOCENTE), Set.of());

        mvc.perform(get("/api/v1/users").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                // El listado no expone datos personales.
                .andExpect(jsonPath("$.items[0].email").doesNotExist());
        mvc.perform(get("/api/v1/users").header("Authorization", bearer(student)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
        mvc.perform(get("/api/v1/users").header("Authorization", bearer(teacher)))
                .andExpect(status().isForbidden());
    }

    @Test
    void coordinatorOnlySeesUsersOfOwnProgram() throws Exception {
        UUID programA = UUID.randomUUID();
        UUID programB = UUID.randomUUID();
        Tokens coordinator = loginAs(Set.of(Role.COORDINADOR), Set.of(programA));
        Tokens counselorA = loginAs(Set.of(Role.CONSEJERO), Set.of(programA));
        Tokens counselorB = loginAs(Set.of(Role.CONSEJERO), Set.of(programB));

        mvc.perform(get("/api/v1/users/" + counselorA.userId()).header("Authorization", bearer(coordinator))
                        .header(REASON, "Revisión de asignación de consejeros"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(org.hamcrest.Matchers.endsWith("@" + DOMAIN)));

        mvc.perform(get("/api/v1/users/" + counselorB.userId()).header("Authorization", bearer(coordinator))
                        .header(REASON, "Intento fuera de alcance"))
                .andExpect(status().isForbidden());
    }

    @Test
    void studentOnlySeesOwnRecord() throws Exception {
        Tokens student = loginAs(Set.of(Role.ESTUDIANTE), Set.of());
        Tokens other = loginAs(Set.of(Role.ESTUDIANTE), Set.of());

        mvc.perform(get("/api/v1/users/" + student.userId()).header("Authorization", bearer(student))
                        .header(REASON, "Consulta de mis datos"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/users/" + other.userId()).header("Authorization", bearer(student))
                        .header(REASON, "Curiosidad"))
                .andExpect(status().isForbidden());
    }

    @Test
    void teacherWithoutGroupsCannotReadOtherUsers() throws Exception {
        Tokens teacher = loginAs(Set.of(Role.DOCENTE), Set.of());
        Tokens student = loginAs(Set.of(Role.ESTUDIANTE), Set.of());
        mvc.perform(get("/api/v1/users/" + student.userId()).header("Authorization", bearer(teacher))
                        .header(REASON, "Seguimiento"))
                .andExpect(status().isForbidden());
    }

    @Test
    void sensitiveReadRequiresReasonAndIsAudited() throws Exception {
        Tokens admin = loginAs(Set.of(Role.ADMIN), Set.of());
        Tokens target = loginAs(Set.of(Role.ESTUDIANTE), Set.of());

        mvc.perform(get("/api/v1/users/" + target.userId()).header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("access_reason_required"));

        mvc.perform(get("/api/v1/users/" + target.userId()).header("Authorization", bearer(admin))
                        .header(REASON, "Soporte técnico ticket 42"))
                .andExpect(status().isOk());

        List<String> outcomes = jdbc.queryForList("""
                SELECT outcome FROM audit_log
                WHERE action = 'SENSITIVE_READ' AND actor_user_id = ? AND entity_id = ? ORDER BY seq
                """, String.class, admin.userId(), target.userId().toString());
        assertThat(outcomes).containsExactly("FAILURE", "SUCCESS");
        String reason = jdbc.queryForObject("""
                SELECT reason FROM audit_log WHERE action = 'SENSITIVE_READ' AND outcome = 'SUCCESS'
                AND actor_user_id = ? ORDER BY seq DESC LIMIT 1
                """, String.class, admin.userId());
        assertThat(reason).isEqualTo("Soporte técnico ticket 42");
    }

    @Test
    void deniedAccessIsAudited() throws Exception {
        Tokens student = loginAs(Set.of(Role.ESTUDIANTE), Set.of());
        mvc.perform(get("/api/v1/users").header("Authorization", bearer(student))).andExpect(status().isForbidden());
        Integer denied = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE outcome = 'DENIED' AND actor_user_id = ?",
                Integer.class, student.userId());
        assertThat(denied).isGreaterThanOrEqualTo(1);
    }

    @Test
    void onlyAdminAssignsRolesAndChangeIsAudited() throws Exception {
        Tokens admin = loginAs(Set.of(Role.ADMIN), Set.of());
        Tokens student = loginAs(Set.of(Role.ESTUDIANTE), Set.of());
        String body = "{\"roles\":[\"DOCENTE\"],\"programIds\":[]}";

        mvc.perform(put("/api/v1/users/" + student.userId() + "/roles").header("Authorization", bearer(student))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/users/" + student.userId() + "/roles").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("DOCENTE"));

        Integer audited = jdbc.queryForObject("""
                SELECT count(*) FROM audit_log WHERE action = 'USER_ROLES_UPDATE' AND outcome = 'SUCCESS'
                AND actor_user_id = ? AND entity_id = ?
                """, Integer.class, admin.userId(), student.userId().toString());
        assertThat(audited).isEqualTo(1);
    }

    @Test
    void coordinatorRoleRequiresProgram() throws Exception {
        Tokens admin = loginAs(Set.of(Role.ADMIN), Set.of());
        Tokens user = loginAs(Set.of(), Set.of());
        mvc.perform(put("/api/v1/users/" + user.userId() + "/roles").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[\"COORDINADOR\"],\"programIds\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminCannotRemoveOwnAdminRole() throws Exception {
        Tokens admin = loginAs(Set.of(Role.ADMIN), Set.of());
        mvc.perform(put("/api/v1/users/" + admin.userId() + "/roles").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[\"DOCENTE\"],\"programIds\":[]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("self_lockout"));
    }

    @Test
    void privilegedRoleInTokenWithoutMfaIsIgnored() throws Exception {
        // Token firmado correctamente pero con mfa=false y rol ADMIN: el rol se descarta.
        Tokens user = loginAs(Set.of(), Set.of());
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(
                new ImmutableSecret<>(new SecretKeySpec(TestSecrets.jwtSecret(), "HmacSHA256")));
        Instant now = clock.instant();
        String forged = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().issuer("retencion-backend").audience(List.of("retencion-api"))
                        .subject(user.userId().toString()).issuedAt(now).expiresAt(now.plusSeconds(600))
                        .claim("sid", UUID.randomUUID().toString()).claim("roles", List.of("ADMIN"))
                        .claim("mfa", false).build())).getTokenValue();
        mvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + forged))
                .andExpect(status().isForbidden());
    }

    @Test
    void actuatorMetricsRequireAdminButHealthIsPublic() throws Exception {
        Tokens admin = loginAs(Set.of(Role.ADMIN), Set.of());
        Tokens student = loginAs(Set.of(Role.ESTUDIANTE), Set.of());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics").header("Authorization", bearer(student)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/metrics").header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
    }
}
