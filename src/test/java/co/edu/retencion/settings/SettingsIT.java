package co.edu.retencion.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

import co.edu.retencion.auth.domain.LocalCredential;
import co.edu.retencion.auth.domain.LocalCredentialRepository;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.support.IntegrationTest;
import co.edu.retencion.support.TestSecrets;

/**
 * Parámetros en caliente: solo ADMIN los edita, con control de versión y tipo, y el cambio se
 * aplica sin redeploy (aquí, el número de intentos antes del bloqueo break-glass).
 */
class SettingsIT extends IntegrationTest {

    private static final String KEY = "auth.lockout.max-attempts";

    @Autowired
    private SettingsProvider settings;
    @Autowired
    private LocalCredentialRepository credentials;
    @Autowired
    private TransactionTemplate tx;

    private long version(Tokens admin) throws Exception {
        String body = mvc.perform(get("/api/v1/settings/" + KEY).header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("version").asLong();
    }

    private void update(Tokens admin, String value, long version, int expectedStatus) throws Exception {
        mvc.perform(put("/api/v1/settings/" + KEY).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":" + value + ",\"version\":" + version + "}"))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void nonAdminCannotReadOrChangeSettings() throws Exception {
        Tokens coordinator = loginAs(Set.of(Role.COORDINADOR), Set.of(java.util.UUID.randomUUID()));
        mvc.perform(get("/api/v1/settings").header("Authorization", bearer(coordinator)))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateValidatesVersionAndType() throws Exception {
        Tokens admin = loginAs(Set.of(Role.ADMIN), Set.of());
        long version = version(admin);
        update(admin, "5", version + 7, 412);
        update(admin, "\"cinco\"", version, 400);
    }

    @Test
    void changeAppliesWithoutRedeploy() throws Exception {
        Tokens admin = loginAs(Set.of(Role.ADMIN), Set.of());
        tx.executeWithoutResult(s -> {
            LocalCredential c = credentials.findByUsername(TestSecrets.BREAK_GLASS_USER).orElseThrow();
            c.rotateSecrets(c.getPasswordHash(), c.getTotpSecret(), clock.instant());
        });
        assertThat(settings.getInt(KEY, -1)).isEqualTo(3);

        update(admin, "1", version(admin), 200);
        try {
            // La caché se invalida al confirmar el cambio: el nuevo valor se ve de inmediato.
            assertThat(settings.getInt(KEY, -1)).isEqualTo(1);
            mvc.perform(post("/api/v1/auth/break-glass/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + TestSecrets.BREAK_GLASS_USER
                                    + "\",\"password\":\"incorrecta\",\"totpCode\":\"123456\"}"))
                    .andExpect(status().isUnauthorized());
            LocalCredential locked = credentials.findByUsername(TestSecrets.BREAK_GLASS_USER).orElseThrow();
            assertThat(locked.getLockedUntil()).as("con max-attempts=1 el primer fallo bloquea").isNotNull();
        } finally {
            update(admin, "3", version(admin), 200);
            tx.executeWithoutResult(s -> {
                LocalCredential c = credentials.findByUsername(TestSecrets.BREAK_GLASS_USER).orElseThrow();
                c.rotateSecrets(c.getPasswordHash(), c.getTotpSecret(), clock.instant());
            });
        }
    }

    @Test
    void listIncludesSeededSettings() throws Exception {
        Tokens admin = loginAs(Set.of(Role.ADMIN), Set.of());
        String body = mvc.perform(get("/api/v1/settings").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode list = json.readTree(body);
        assertThat(list.findValuesAsText("key")).contains(KEY, "auth.lockout.base-lock-seconds");
    }

    @Test
    void invalidKeyFormatIsRejected() throws Exception {
        Tokens admin = loginAs(Set.of(Role.ADMIN), Set.of());
        mvc.perform(get("/api/v1/settings/CLAVE_INVALIDA").header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
    }
}
