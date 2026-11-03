package co.edu.retencion.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.users.domain.User;
import co.edu.retencion.users.domain.UserRepository;

/**
 * Base de las pruebas de integración: contexto Spring completo, PostgreSQL real, Supabase Auth
 * simulado y reloj controlable. Incluye utilidades para iniciar sesión con distintos roles.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IntegrationTest.TestClockConfig.class)
public abstract class IntegrationTest {

    protected static final FakeSupabase SUPABASE = FakeSupabase.get();
    protected static final String DOMAIN = "universidad.edu.co";

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;
    @Autowired
    protected MutableClock clock;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TransactionTemplate tx;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfig {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        TestDatabase.get().register(registry);
        SUPABASE.register(registry);
        TestSecrets.register(registry);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    /** Tokens de sesión devueltos por la API. */
    public record Tokens(String accessToken, String refreshToken, UUID userId) {
    }

    /** Canjea un access token de Supabase por la sesión de la plataforma. */
    protected ResultActions exchange(String supabaseToken) throws Exception {
        return mvc.perform(post("/api/v1/auth/supabase/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new ExchangeBody(supabaseToken))));
    }

    private record ExchangeBody(String accessToken) {
    }

    /** Emite un token de Supabase para un usuario con correo confirmado y programa /auth/v1/user. */
    protected String supabaseToken(String subject, String email, String aal) {
        String token = SUPABASE.sign(FakeSupabase.AccessToken.of(subject, email, aal, clock.instant()));
        SUPABASE.willReturnUser(token, subject, email, true);
        return token;
    }

    /** Flujo completo: login en Supabase simulado + canje. */
    protected Tokens loginWithSupabase(String subject, String email, String aal) throws Exception {
        String body = exchange(supabaseToken(subject, email, aal)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return toTokens(body);
    }

    /** Crea un usuario nuevo con los roles y programas dados y devuelve una sesión con MFA. */
    protected Tokens loginAs(Set<Role> roles, Set<UUID> programs) throws Exception {
        String subject = UUID.randomUUID().toString();
        String email = "u" + subject.substring(0, 8) + "@" + DOMAIN;
        Tokens first = loginWithSupabase(subject, email, "aal2");
        assignRoles(first.userId(), roles, programs);
        return loginWithSupabase(subject, email, "aal2");
    }

    protected void assignRoles(UUID userId, Set<Role> roles, Set<UUID> programs) {
        tx.executeWithoutResult(status -> {
            User user = userRepository.findById(userId).orElseThrow();
            user.replaceRoles(roles, null, clock.instant());
            user.replacePrograms(programs, clock.instant());
        });
    }

    protected Tokens toTokens(String body) throws Exception {
        JsonNode node = json.readTree(body);
        return new Tokens(node.get("accessToken").asText(), node.get("refreshToken").asText(),
                UUID.fromString(node.get("userId").asText()));
    }

    protected static String bearer(Tokens tokens) {
        return "Bearer " + tokens.accessToken();
    }
}
