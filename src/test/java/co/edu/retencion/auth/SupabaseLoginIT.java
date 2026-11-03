package co.edu.retencion.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.support.FakeSupabase.AccessToken;
import co.edu.retencion.support.IntegrationTest;

/**
 * Login con Supabase Auth (simulado): cualquier correo verificado, validación del token,
 * anti-replay, aprovisionamiento y MFA ({@code aal2}) obligatorio para roles privilegiados.
 */
class SupabaseLoginIT extends IntegrationTest {

    private static String newSubject() {
        return UUID.randomUUID().toString();
    }

    @Test
    void anyVerifiedEmailCanSignInAndIsProvisionedWithoutRoles() throws Exception {
        String sub = newSubject();
        Tokens tokens = loginWithSupabase(sub, "persona-" + sub.substring(0, 6) + "@gmail.com", "aal1");

        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank();
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(tokens)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tokens.userId().toString()))
                .andExpect(jsonPath("$.roles").isEmpty())
                .andExpect(jsonPath("$.displayName").value("Persona de Prueba"))
                .andExpect(jsonPath("$.authType").value("SUPABASE"));
    }

    @Test
    void secondLoginReusesSameInternalUser() throws Exception {
        String sub = newSubject();
        String email = "repite-" + sub.substring(0, 6) + "@outlook.com";
        Tokens first = loginWithSupabase(sub, email, "aal1");
        Tokens second = loginWithSupabase(sub, email, "aal1");
        assertThat(second.userId()).isEqualTo(first.userId());
    }

    @Test
    void domainRuleAssignsDefaultStudentRole() throws Exception {
        String sub = newSubject();
        Tokens tokens = loginWithSupabase(sub, "est-" + sub.substring(0, 6) + "@estudiantes." + DOMAIN, "aal1");
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(tokens)))
                .andExpect(jsonPath("$.roles[0]").value("ESTUDIANTE"));
    }

    @Test
    void unverifiedEmailIsRejected() throws Exception {
        String sub = newSubject();
        String email = "sinconfirmar-" + sub.substring(0, 6) + "@gmail.com";
        String token = SUPABASE.sign(AccessToken.of(sub, email, "aal1", clock.instant()));
        SUPABASE.willReturnUser(token, sub, email, false);
        exchange(token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("email_not_verified"));
    }

    @Test
    void tokenCanOnlyBeExchangedOnce() throws Exception {
        String sub = newSubject();
        String token = supabaseToken(sub, "una-vez-" + sub.substring(0, 6) + "@gmail.com", "aal1");
        exchange(token).andExpect(status().isOk());
        exchange(token)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("provider_token_already_used"));
    }

    @Test
    void rejectsForgedSignature() throws Exception {
        String sub = newSubject();
        String token = SUPABASE.sign(AccessToken.of(sub, "x@gmail.com", "aal1", clock.instant()).withRogueSignature());
        exchange(token)
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("invalid_provider_token"));
    }

    @Test
    void rejectsTokenFromAnotherProject() throws Exception {
        String token = SUPABASE.sign(AccessToken.of(newSubject(), "x@gmail.com", "aal1", clock.instant())
                .withIssuer("https://otro-proyecto.supabase.co/auth/v1"));
        exchange(token).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_provider_token"));
    }

    @Test
    void rejectsWrongAudienceAndAnonymousUsers() throws Exception {
        exchange(SUPABASE.sign(AccessToken.of(newSubject(), "x@gmail.com", "aal1", clock.instant()).withAudience("anon")))
                .andExpect(status().isUnauthorized());
        exchange(SUPABASE.sign(AccessToken.of(newSubject(), "x@gmail.com", "aal1", clock.instant()).anonymousUser()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        String token = SUPABASE.sign(AccessToken.of(newSubject(), "x@gmail.com", "aal1",
                clock.instant().minus(Duration.ofHours(2))));
        exchange(token).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_provider_token"));
    }

    @Test
    void revokedSupabaseSessionIsRejected() throws Exception {
        String token = SUPABASE.sign(AccessToken.of(newSubject(), "x@gmail.com", "aal1", clock.instant()));
        SUPABASE.willFailUser(token, 401);
        exchange(token).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_provider_token"));
    }

    @Test
    void supabaseOutageIsReportedAsBadGateway() throws Exception {
        String token = SUPABASE.sign(AccessToken.of(newSubject(), "x@gmail.com", "aal1", clock.instant()));
        SUPABASE.willFailUser(token, 500);
        exchange(token).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("auth_provider_error"));
    }

    @Test
    void privilegedRoleWithoutMfaIsRejectedWithClearError() throws Exception {
        String sub = newSubject();
        String email = "coord-" + sub.substring(0, 6) + "@gmail.com";
        Tokens first = loginWithSupabase(sub, email, "aal1");
        assignRoles(first.userId(), Set.of(Role.COORDINADOR), Set.of(UUID.randomUUID()));

        exchange(supabaseToken(sub, email, "aal1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("mfa_required"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("multifactor")))
                .andExpect(jsonPath("$.rolesRequiringMfa[0]").value("COORDINADOR"));
    }

    @Test
    void privilegedRoleWithMfaLogsIn() throws Exception {
        Tokens admin = loginAs(Set.of(Role.ADMIN), Set.of());
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"))
                .andExpect(jsonPath("$.mfa").value(true));
    }

    @Test
    void responsesCarryRequestId() throws Exception {
        mvc.perform(get("/api/v1/users/me").header("X-Request-Id", "prueba-request-1234"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Request-Id", "prueba-request-1234"))
                .andExpect(jsonPath("$.code").value("unauthorized"));
    }
}
