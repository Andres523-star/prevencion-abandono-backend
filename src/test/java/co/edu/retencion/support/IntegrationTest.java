package co.edu.retencion.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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

    // ------------------------------------------------------------------ utilidades de la Fase 2

    /** Programas sembrados por la migración V2. */
    protected static final UUID ENF = UUID.fromString("0195f000-0000-7000-8000-000000000001");
    protected static final UUID MED = UUID.fromString("0195f000-0000-7000-8000-000000000002");
    protected static final UUID DER = UUID.fromString("0195f000-0000-7000-8000-000000000003");
    protected static final UUID ISW = UUID.fromString("0195f000-0000-7000-8000-000000000004");

    protected static String uniqueCode(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    protected ResultActions postJson(String path, Tokens tokens, Object body) throws Exception {
        return mvc.perform(post(path).header("Authorization", bearer(tokens))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    protected JsonNode read(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    protected UUID createTerm(Tokens admin) throws Exception {
        return UUID.fromString(read(postJson("/api/v1/terms", admin, java.util.Map.of(
                "code", uniqueCode("T-"), "name", "Periodo de prueba", "startDate", "2026-08-03",
                "endDate", "2026-11-27", "status", "ACTIVE"))
                .andExpect(status().isCreated())).get("id").asText());
    }

    protected UUID createSubject(Tokens admin, UUID programId) throws Exception {
        return UUID.fromString(read(postJson("/api/v1/subjects", admin, java.util.Map.of(
                "programId", programId, "code", uniqueCode("S-"), "name", "Materia de prueba", "credits", 3,
                "suggestedSemester", 1, "prerequisiteIds", List.of()))
                .andExpect(status().isCreated())).get("id").asText());
    }

    /** Grupo regular con clase los lunes y miércoles de 7 a 9. */
    protected UUID createGroup(Tokens admin, UUID subjectId, UUID termId, UUID teacherId, int capacity)
            throws Exception {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("subjectId", subjectId);
        body.put("termId", termId);
        body.put("code", uniqueCode("G"));
        body.put("teacherUserId", teacherId);
        body.put("capacity", capacity);
        body.put("groupType", "REGULAR");
        body.put("schedule", List.of(
                java.util.Map.of("day", "MONDAY", "start", "07:00", "end", "09:00", "room", "A-101"),
                java.util.Map.of("day", "WEDNESDAY", "start", "07:00", "end", "09:00", "room", "A-101")));
        return UUID.fromString(read(postJson("/api/v1/groups", admin, body).andExpect(status().isCreated()))
                .get("id").asText());
    }

    /** Registra un estudiante activo; {@code email} puede ser {@code null}. */
    protected UUID createStudent(Tokens admin, UUID programId, String email) throws Exception {
        java.util.Map<String, Object> identity = new java.util.HashMap<>();
        identity.put("fullName", "Estudiante de Prueba");
        identity.put("documentType", "CC");
        identity.put("documentNumber", String.valueOf(1_000_000_000L + (long) (Math.random() * 8_999_999_999L)));
        identity.put("email", email);
        identity.put("phone", "3001234567");
        return UUID.fromString(read(postJson("/api/v1/students", admin, java.util.Map.of(
                "identity", identity, "programId", programId, "currentSemester", 2, "status", "ACTIVO",
                "admissionDate", "2025-08-01",
                "socioeconomic", java.util.Map.of("stratum", 2, "works", true, "dependents", 0,
                        "transportMode", "TRANSPORTE_PUBLICO")))
                .andExpect(status().isCreated())).get("id").asText());
    }

    protected void enroll(Tokens admin, UUID studentId, UUID groupId) throws Exception {
        postJson("/api/v1/students/" + studentId + "/enrollments", admin, java.util.Map.of("groupId", groupId))
                .andExpect(status().isCreated());
    }
}
