package co.edu.retencion.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import co.edu.retencion.auth.domain.LocalCredential;
import co.edu.retencion.auth.domain.LocalCredentialRepository;
import co.edu.retencion.auth.domain.Totp;
import co.edu.retencion.support.IntegrationTest;
import co.edu.retencion.support.TestSecrets;

/**
 * Cuenta break-glass: argon2id + TOTP, anti-replay, mensajes que no permiten enumerar usuarios
 * y bloqueo progresivo.
 */
class BreakGlassIT extends IntegrationTest {

    @Autowired
    private LocalCredentialRepository credentials;
    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void resetAccount() {
        // Estado limpio: sin fallos, sin bloqueo y sin paso TOTP usado.
        tx.executeWithoutResult(s -> {
            LocalCredential c = credentials.findByUsername(TestSecrets.BREAK_GLASS_USER).orElseThrow();
            c.rotateSecrets(c.getPasswordHash(), c.getTotpSecret(), clock.instant());
        });
    }

    private String currentCode() {
        return Totp.generate(TestSecrets.TOTP_SECRET, Totp.stepAt(clock.instant()));
    }

    private ResultActions login(String username, String password, String code) throws Exception {
        return mvc.perform(post("/api/v1/auth/break-glass/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new Body(username, password, code))));
    }

    private record Body(String username, String password, String totpCode) {
    }

    private LocalCredential credential() {
        return credentials.findByUsername(TestSecrets.BREAK_GLASS_USER).orElseThrow();
    }

    @Test
    void loginWithPasswordAndTotpOpensAdminSessionWithMfa() throws Exception {
        String body = login(TestSecrets.BREAK_GLASS_USER, TestSecrets.BREAK_GLASS_PASSWORD, currentCode())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Tokens tokens = toTokens(body);
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(tokens)))
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"))
                .andExpect(jsonPath("$.mfa").value(true))
                .andExpect(jsonPath("$.authType").value("LOCAL"));
    }

    @Test
    void wrongTotpIsRejected() throws Exception {
        login(TestSecrets.BREAK_GLASS_USER, TestSecrets.BREAK_GLASS_PASSWORD, "000000")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_credentials"));
    }

    @Test
    void unknownUserGetsSameGenericError() throws Exception {
        login("no-existe", "cualquier-cosa-larga", "123456")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_credentials"))
                .andExpect(jsonPath("$.detail").value("Usuario, contraseña o código incorrectos"));
    }

    @Test
    void totpCodeCannotBeReplayed() throws Exception {
        String code = currentCode();
        login(TestSecrets.BREAK_GLASS_USER, TestSecrets.BREAK_GLASS_PASSWORD, code).andExpect(status().isOk());
        login(TestSecrets.BREAK_GLASS_USER, TestSecrets.BREAK_GLASS_PASSWORD, code)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void accountLocksAfterThreeFailuresAndUnlocksLater() throws Exception {
        for (int i = 0; i < 3; i++) {
            login(TestSecrets.BREAK_GLASS_USER, "contraseña-incorrecta", currentCode()).andExpect(status().isUnauthorized());
        }
        LocalCredential locked = credential();
        assertThat(locked.getLockedUntil()).isCloseTo(clock.instant().plusSeconds(60), within(5, ChronoUnit.SECONDS));

        // Bloqueada: ni con la contraseña correcta.
        login(TestSecrets.BREAK_GLASS_USER, TestSecrets.BREAK_GLASS_PASSWORD, currentCode())
                .andExpect(status().isUnauthorized());

        clock.advance(Duration.ofSeconds(61));
        login(TestSecrets.BREAK_GLASS_USER, TestSecrets.BREAK_GLASS_PASSWORD, currentCode())
                .andExpect(status().isOk());
        assertThat(credential().getFailedAttempts()).isZero();
    }

    @Test
    void lockDurationDoublesWithEachAdditionalFailure() throws Exception {
        for (int i = 0; i < 3; i++) {
            login(TestSecrets.BREAK_GLASS_USER, "mal", "111111");
        }
        clock.advance(Duration.ofSeconds(61));
        login(TestSecrets.BREAK_GLASS_USER, "mal-otra-vez", "111111").andExpect(status().isUnauthorized());

        assertThat(credential().getFailedAttempts()).isEqualTo(4);
        assertThat(credential().getLockedUntil())
                .isCloseTo(clock.instant().plusSeconds(120), within(5, ChronoUnit.SECONDS));
    }
}
