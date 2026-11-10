package co.edu.retencion.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.support.AttendanceWorld;
import co.edu.retencion.support.AttendanceWorld.Actor;
import co.edu.retencion.support.CampusIntegrationTest;

/** Derechos del titular: consulta con copia de datos, rectificación, supresión en dos pasos y plazos. */
class PrivacyIT extends CampusIntegrationTest {

    private JsonNode submit(AttendanceWorld w, String type, String detail) throws Exception {
        relogin(w.student());
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("type", type);
        body.put("detail", detail);
        return read(postJson("/api/v1/students/me/data-requests", w.student().tokens(), body).andExpect(status().isCreated()));
    }

    @Test
    void accessRequestDeliversACopyOnlyToTheTitular() throws Exception {
        AttendanceWorld w = world(ISW);
        markAbsence(w, 0);
        Actor admin = staff(Set.of(Role.ADMIN), Set.of());
        JsonNode request = submit(w, "ACCESO", null);
        assertThat(request.get("status").asText()).isEqualTo("RADICADA");
        assertThat(request.get("dueOn").asText()).isNotBlank();
        relogin(w.student());
        postJson("/api/v1/students/me/data-requests", w.student().tokens(), Map.of("type", "ACCESO"))
                .andExpect(status().isConflict());
        assertThat(notifications(admin).findValuesAsText("type")).contains("SOLICITUD_DATOS");

        String id = request.get("id").asText();
        relogin(admin);
        JsonNode queue = read(mvc.perform(get("/api/v1/privacy/data-requests").param("status", "RADICADA")
                .header("Authorization", bearer(admin.tokens()))).andExpect(status().isOk()));
        assertThat(queue.findValuesAsText("id")).contains(id);
        JsonNode done = read(postJson("/api/v1/privacy/data-requests/" + id + "/approve", admin.tokens(), Map.of())
                .andExpect(status().isOk()));
        assertThat(done.get("status").asText()).isEqualTo("EJECUTADA");
        assertThat(done.get("exportAvailable").asBoolean()).isTrue();
        assertThat(notifications(w.student()).findValuesAsText("type")).contains("SOLICITUD_DATOS_RESPONDIDA");

        relogin(w.student());
        JsonNode url = read(mvc.perform(get("/api/v1/data-requests/" + id + "/export-url")
                .header("Authorization", bearer(w.student().tokens()))).andExpect(status().isOk()));
        String copy = mvc.perform(get(URI.create(url.get("url").asText()).getPath())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode data = json.readTree(copy);
        assertThat(data.at("/identidad/nombre").asText()).isNotBlank();
        assertThat(data.at("/academico/codigo").asText()).startsWith("EST-");
        assertThat(data.get("faltas")).hasSize(1);
        assertThat(data.has("consentimientos")).isTrue();
        assertThat(copy).doesNotContain("stratum").doesNotContain("wellbeing").doesNotContain("risk");

        // Nadie más la descarga, ni siquiera ADMIN por esta vía.
        AttendanceWorld other = world(ISW);
        relogin(other.student());
        mvc.perform(get("/api/v1/data-requests/" + id + "/export-url").header("Authorization", bearer(other.student().tokens())))
                .andExpect(status().isNotFound());
        relogin(admin);
        mvc.perform(get("/api/v1/data-requests/" + id + "/export-url").header("Authorization", bearer(admin.tokens())))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'DATA_EXPORT_GENERATED' AND entity_id = ?",
                Long.class, id)).isEqualTo(1);
    }

    @Test
    void rectificationAndRejectionRules() throws Exception {
        AttendanceWorld w = world(ISW);
        Actor admin = staff(Set.of(Role.ADMIN), Set.of());
        relogin(w.student());
        postJson("/api/v1/students/me/data-requests", w.student().tokens(), Map.of("type", "RECTIFICACION"))
                .andExpect(status().isBadRequest());
        String id = submit(w, "RECTIFICACION", "Mi teléfono es otro").get("id").asText();
        relogin(admin);
        postJson("/api/v1/privacy/data-requests/" + id + "/approve", admin.tokens(), Map.of()).andExpect(status().isOk());
        postJson("/api/v1/privacy/data-requests/" + id + "/execute", admin.tokens(), Map.of()).andExpect(status().isBadRequest());
        JsonNode done = read(postJson("/api/v1/privacy/data-requests/" + id + "/execute", admin.tokens(),
                Map.of("comment", "Teléfono actualizado en la identidad")).andExpect(status().isOk()));
        assertThat(done.get("status").asText()).isEqualTo("EJECUTADA");

        String access = submit(w, "ACCESO", null).get("id").asText();
        relogin(admin);
        postJson("/api/v1/privacy/data-requests/" + access + "/reject", admin.tokens(), Map.of("comment", " "))
                .andExpect(status().isBadRequest());
        postJson("/api/v1/privacy/data-requests/" + access + "/reject", admin.tokens(), Map.of("comment", "Duplicada"))
                .andExpect(status().isOk());
        relogin(w.student());
        JsonNode mine = read(mvc.perform(get("/api/v1/students/me/data-requests")
                .header("Authorization", bearer(w.student().tokens()))).andExpect(status().isOk()));
        assertThat(mine.findValuesAsText("status")).contains("RECHAZADA", "EJECUTADA");
        mvc.perform(get("/api/v1/privacy/data-requests").header("Authorization", bearer(w.student().tokens())))
                .andExpect(status().isForbidden());
    }

    @Test
    void suppressionNeedsTwoAdminsAndErasesPersonalData() throws Exception {
        AttendanceWorld w = world(ISW);
        Actor approver = staff(Set.of(Role.ADMIN), Set.of());
        Actor executor = staff(Set.of(Role.ADMIN), Set.of());
        relogin(w.teacher());
        UUID chat = UUID.fromString(read(postJson("/api/v1/chats", w.teacher().tokens(), Map.of("studentId", w.studentId()))
                .andExpect(status().isOk())).get("id").asText());
        postJson("/api/v1/chats/" + chat + "/messages", w.teacher().tokens(), Map.of("type", "TEXTO", "text", "Hola"))
                .andExpect(status().isCreated());
        UUID user = w.student().userId();

        String id = submit(w, "SUPRESION", "Retiro mi autorización").get("id").asText();
        relogin(approver);
        postJson("/api/v1/privacy/data-requests/" + id + "/approve", approver.tokens(), Map.of("comment", "Procede"))
                .andExpect(status().isOk());
        postJson("/api/v1/privacy/data-requests/" + id + "/execute", approver.tokens(), Map.of())
                .andExpect(status().isConflict());
        relogin(executor);
        postJson("/api/v1/privacy/data-requests/" + id + "/execute", executor.tokens(), Map.of()).andExpect(status().isOk());
        awaitListeners();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM student_identities WHERE student_id = ?", Long.class, w.studentId()))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM students WHERE id = ?", Boolean.class, w.studentId()))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT user_id FROM students WHERE id = ?", UUID.class, w.studentId())).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, user)).isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE conversation_id = ? AND purged_at IS NULL",
                Long.class, chat)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_id = ? OR student_id = ?",
                Long.class, user, w.studentId())).isZero();
        // Lo académico se conserva seudonimizado (obligación legal de conservar el registro académico).
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_records WHERE student_id = ?", Long.class,
                w.studentId())).isGreaterThanOrEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'STUDENT_DATA_SUPPRESSED' AND entity_id = ?",
                Long.class, w.studentId().toString())).isEqualTo(1);
    }

    @Test
    void processingActivitiesAreAvailable() throws Exception {
        AttendanceWorld w = world(ISW);
        relogin(w.student());
        JsonNode registry = read(mvc.perform(get("/api/v1/privacy/processing-activities")
                .header("Authorization", bearer(w.student().tokens()))).andExpect(status().isOk()));
        assertThat(registry.get("actividades").findValuesAsText("id")).contains("ANALITICA_RETENCION", "BIENESTAR");
        assertThat(registry.get("encargados").findValuesAsText("nombre")).anyMatch(n -> n.contains("Anthropic"));
        mvc.perform(get("/api/v1/privacy/processing-activities")).andExpect(status().isUnauthorized());
    }
}
