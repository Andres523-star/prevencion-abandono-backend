package co.edu.retencion.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.support.IntegrationTest;

/**
 * Sesión propia: rotación del refresh token, detección de reutilización (revoca la familia),
 * vencimientos y logout.
 */
class RefreshTokenIT extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"));
    }

    private Tokens newSession() throws Exception {
        String oid = UUID.randomUUID().toString();
        return loginWithSupabase(oid, "r-" + oid.substring(0, 8) + "@" + DOMAIN, "aal1");
    }

    @Test
    void refreshRotatesTokens() throws Exception {
        Tokens session = newSession();
        Tokens rotated = toTokens(refresh(session.refreshToken()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(rotated.refreshToken()).isNotEqualTo(session.refreshToken());
        assertThat(rotated.userId()).isEqualTo(session.userId());
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(rotated))).andExpect(status().isOk());
    }

    @Test
    void reusingRotatedTokenRevokesWholeFamily() throws Exception {
        Tokens session = newSession();
        Tokens rotated = toTokens(refresh(session.refreshToken()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        // Un atacante presenta el token viejo: reutilización detectada.
        refresh(session.refreshToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("refresh_token_reused"));

        // El token legítimo más reciente también quedó revocado.
        refresh(rotated.refreshToken()).andExpect(status().isUnauthorized());

        Integer audits = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'AUTH_REFRESH_REUSE_DETECTED' AND actor_user_id = ?",
                Integer.class, session.userId());
        assertThat(audits).isGreaterThanOrEqualTo(1);
    }

    @Test
    void unknownTokenIsRejected() throws Exception {
        refresh("token-inventado-que-no-existe")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_refresh_token"));
    }

    @Test
    void expiredRefreshTokenIsRejected() throws Exception {
        Tokens session = newSession();
        clock.advance(Duration.ofHours(9));
        refresh(session.refreshToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_refresh_token"));
    }

    @Test
    void accessTokenExpiresAfterFifteenMinutes() throws Exception {
        Tokens session = newSession();
        clock.advance(Duration.ofMinutes(16));
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(session)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        Tokens session = newSession();
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer(session)))
                .andExpect(status().isNoContent());
        refresh(session.refreshToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void disabledUserCannotRefreshOrLogIn() throws Exception {
        String oid = UUID.randomUUID().toString();
        String email = "baja-" + oid.substring(0, 8) + "@" + DOMAIN;
        Tokens session = loginWithSupabase(oid, email, "aal1");
        Tokens admin = loginAs(Set.of(Role.ADMIN), Set.of());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/users/" + session.userId() + "/status")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        refresh(session.refreshToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("account_disabled"));

        exchange(supabaseToken(oid, email, "aal1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("account_disabled"));
    }

    @Test
    void refreshRequiresMfaWhenPrivilegedRoleWasGrantedLater() throws Exception {
        Tokens session = newSession();
        assignRoles(session.userId(), Set.of(Role.CONSEJERO), Set.of(UUID.randomUUID()));
        refresh(session.refreshToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"));
    }
}
