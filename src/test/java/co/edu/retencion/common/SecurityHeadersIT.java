package co.edu.retencion.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.support.IntegrationTest;

/**
 * Seguridad transversal: cabeceras, CORS estricto, errores RFC 7807, validación de entrada y
 * cifrado de datos personales en reposo.
 */
class SecurityHeadersIT extends IntegrationTest {

    private static final String ALLOWED_ORIGIN = "https://app.universidad.edu.co";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void apiResponsesCarrySecurityHeaders() throws Exception {
        mvc.perform(get("/actuator/health").secure(true))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("default-src 'none'")))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().exists("Permissions-Policy"))
                .andExpect(header().string("Strict-Transport-Security", org.hamcrest.Matchers.containsString("max-age=31536000")));
    }

    @Test
    void corsAllowsOnlyConfiguredOrigins() throws Exception {
        mvc.perform(options("/api/v1/users/me").header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));
        mvc.perform(options("/api/v1/users/me").header("Origin", "https://sitio-malicioso.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void malformedJsonReturnsProblemDetail() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{no es json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:retencion:problem:bad_request"))
                .andExpect(jsonPath("$.code").value("bad_request"));
    }

    @Test
    void unknownFieldsAreRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"x\",\"admin\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void beanValidationErrorsListFields() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.errors[0].field").value("refreshToken"));
    }

    @Test
    void unknownRouteReturnsNotFoundProblem() throws Exception {
        Tokens admin = loginAs(Set.of(Role.ADMIN), Set.of());
        mvc.perform(get("/api/v1/no-existe").header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    void personalDataIsEncryptedAtRest() throws Exception {
        String oid = UUID.randomUUID().toString();
        String email = "cifrado-" + oid.substring(0, 8) + "@" + DOMAIN;
        Tokens tokens = loginWithSupabase(oid, email, "aal1");

        var row = jdbc.queryForMap("SELECT email_ciphertext, email_blind_index, display_name_ciphertext FROM users WHERE id = ?",
                tokens.userId());
        assertThat((String) row.get("email_ciphertext")).startsWith("v1.").doesNotContain(email).doesNotContain("@");
        assertThat((String) row.get("display_name_ciphertext")).startsWith("v1.").doesNotContain("Persona");
        assertThat((String) row.get("email_blind_index")).hasSize(64).doesNotContain(email);
    }
}
