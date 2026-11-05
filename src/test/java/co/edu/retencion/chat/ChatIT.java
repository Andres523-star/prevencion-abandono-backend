package co.edu.retencion.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.support.AttendanceWorld;
import co.edu.retencion.support.AttendanceWorld.Actor;
import co.edu.retencion.support.CampusIntegrationTest;
import co.edu.retencion.support.FakeClaude;
import co.edu.retencion.support.OfficeFiles;

/**
 * Chat docente-estudiante por REST: reglas de acceso, mensajes, no leídos, adjuntos (permitidos y
 * rechazados), lectura de ADMIN con motivo, solo lectura tras retirar la inscripción, vista dividida,
 * enlace de cita y que el texto del chat nunca llega a la IA.
 */
class ChatIT extends CampusIntegrationTest {

    private UUID open(Actor teacher, UUID studentId) throws Exception {
        return UUID.fromString(read(postJson("/api/v1/chats", teacher.tokens(), Map.of("studentId", studentId))
                .andExpect(status().isOk())).get("id").asText());
    }

    private ResultActions send(Actor actor, UUID conversation, String type, String text, String attachmentId)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("type", type);
        body.put("text", text);
        body.put("attachmentId", attachmentId);
        return postJson("/api/v1/chats/" + conversation + "/messages", actor.tokens(), body);
    }

    @Test
    void onlyTheStudentsTeacherOpensAndOnlyParticipantsRead() throws Exception {
        AttendanceWorld w = world(ISW);
        Actor stranger = staff(Set.of(Role.DOCENTE), Set.of());
        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of(ISW));

        postJson("/api/v1/chats", stranger.tokens(), Map.of("studentId", w.studentId())).andExpect(status().isForbidden());
        postJson("/api/v1/chats", counselor.tokens(), Map.of("studentId", w.studentId())).andExpect(status().isForbidden());
        postJson("/api/v1/chats", w.student().tokens(), Map.of("studentId", w.studentId())).andExpect(status().isForbidden());

        UUID chat = open(w.teacher(), w.studentId());
        assertThat(open(w.teacher(), w.studentId())).as("una sola conversación por par").isEqualTo(chat);

        JsonNode sent = read(send(w.teacher(), chat, "TEXTO", "Hola, ¿cómo vas con las faltas?", null)
                .andExpect(status().isCreated()));
        assertThat(sent.get("text").asText()).isEqualTo("Hola, ¿cómo vas con las faltas?");
        String stored = jdbc.queryForObject("SELECT text_ciphertext FROM chat_messages WHERE id = ?", String.class,
                UUID.fromString(sent.get("id").asText()));
        assertThat(stored).as("cifrado en reposo").startsWith("v1.").doesNotContain("faltas");

        // El estudiante ve la conversación con un no leído y responde.
        JsonNode mine = read(mvc.perform(get("/api/v1/chats").header("Authorization", bearer(w.student().tokens())))
                .andExpect(status().isOk()));
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("unread").asLong()).isEqualTo(1);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/chats/" + chat + "/read")
                .header("Authorization", bearer(w.student().tokens()))).andExpect(status().isNoContent());
        assertThat(read(mvc.perform(get("/api/v1/chats").header("Authorization", bearer(w.student().tokens()))))
                .get(0).get("unread").asLong()).isZero();
        String reply = read(send(w.student(), chat, "TEXTO", "Bien, profe", null).andExpect(status().isCreated()))
                .get("id").asText();
        assertThat(notifications(w.teacher()).findValuesAsText("type")).contains("NUEVO_MENSAJE");

        // Nadie más lee el chat.
        for (Actor outsider : new Actor[]{stranger, counselor, w.admin()}) {
            mvc.perform(get("/api/v1/chats/" + chat + "/messages").header("Authorization", bearer(outsider.tokens())))
                    .andExpect(status().isForbidden());
        }
        send(stranger, chat, "TEXTO", "intruso", null).andExpect(status().isForbidden());

        // ADMIN solo con motivo, y queda auditado.
        mvc.perform(get("/api/v1/admin/chats/" + chat + "/messages").header("Authorization", bearer(w.admin().tokens())))
                .andExpect(status().isBadRequest());
        JsonNode adminPage = read(mvc.perform(get("/api/v1/admin/chats/" + chat + "/messages")
                .header("Authorization", bearer(w.admin().tokens())).header("X-Access-Reason", "Reporte de acoso"))
                .andExpect(status().isOk()));
        assertThat(adminPage.get("items")).hasSize(2);
        Integer audited = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_type = 'ChatMessage' "
                + "AND entity_id = ? AND reason = 'Reporte de acoso'", Integer.class, chat.toString());
        assertThat(audited).isEqualTo(1);

        // Reportar, borrar el propio (no el ajeno).
        postJson("/api/v1/chats/" + chat + "/messages/" + reply + "/report", w.teacher().tokens(),
                Map.of("reason", "Prueba de reporte")).andExpect(status().isCreated());
        assertThat(read(mvc.perform(get("/api/v1/admin/chat-reports").header("Authorization", bearer(w.admin().tokens()))))
                .findValuesAsText("messageId")).contains(reply);
        mvc.perform(delete("/api/v1/chats/" + chat + "/messages/" + reply).header("Authorization", bearer(w.teacher().tokens())))
                .andExpect(status().is4xxClientError());
        JsonNode deleted = read(mvc.perform(delete("/api/v1/chats/" + chat + "/messages/" + reply)
                .header("Authorization", bearer(w.student().tokens()))).andExpect(status().isOk()));
        assertThat(deleted.get("deleted").asBoolean()).isTrue();
        assertThat(deleted.get("text").isNull()).isTrue();

        // Retirada la inscripción, la conversación queda de solo lectura.
        postJson("/api/v1/enrollments/" + w.enrollmentId() + "/withdraw", w.admin().tokens(), Map.of())
                .andExpect(status().isOk());
        send(w.teacher(), chat, "TEXTO", "¿Sigues ahí?", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("chat_read_only"));
        mvc.perform(get("/api/v1/chats/" + chat + "/messages").header("Authorization", bearer(w.teacher().tokens())))
                .andExpect(status().isOk());
        postJson("/api/v1/chats", w.teacher().tokens(), Map.of("studentId", w.studentId())).andExpect(status().isForbidden());
    }

    @Test
    void attachmentsAreValidatedScannedAndDownloadedOnlyByParticipants() throws Exception {
        AttendanceWorld w = world(ISW);
        Actor stranger = staff(Set.of(Role.DOCENTE), Set.of());
        UUID chat = open(w.teacher(), w.studentId());

        // Rechazados: .docm, .doc (OLE), docx con macros, extensión falsa, EICAR, sin duración de audio.
        upload(w.teacher(), chat, "tarea.docm", OfficeFiles.docx(), null).andExpect(status().isBadRequest());
        upload(w.teacher(), chat, "tarea.doc", OfficeFiles.OLE_DOC, null).andExpect(status().isBadRequest());
        upload(w.teacher(), chat, "renombrado.docx", OfficeFiles.OLE_DOC, null).andExpect(status().isBadRequest());
        upload(w.teacher(), chat, "tarea.docx", OfficeFiles.docxWithMacros(), null).andExpect(status().isBadRequest());
        upload(w.teacher(), chat, "tarea.docx", OfficeFiles.docmContentType(), null).andExpect(status().isBadRequest());
        upload(w.teacher(), chat, "foto.pdf", OfficeFiles.MP3, null).andExpect(status().isBadRequest());
        upload(w.teacher(), chat, "programa.exe", OfficeFiles.PDF, null).andExpect(status().isBadRequest());
        byte[] infected = ("%PDF-1.4\n" + new String(OfficeFiles.EICAR, StandardCharsets.US_ASCII))
                .getBytes(StandardCharsets.US_ASCII);
        upload(w.teacher(), chat, "malware.pdf", infected, null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("chat_file_infected"));
        upload(w.student(), chat, "nota.ogg", OfficeFiles.OGG, null).andExpect(status().isBadRequest());
        upload(w.student(), chat, "nota.ogg", OfficeFiles.OGG, 100_000).andExpect(status().isBadRequest());
        Integer stored = jdbc.queryForObject("SELECT count(*) FROM chat_attachments WHERE conversation_id = ?",
                Integer.class, chat);
        assertThat(stored).as("ningún archivo rechazado se guarda").isZero();
        upload(stranger, chat, "plan.pdf", OfficeFiles.PDF, null).andExpect(status().isForbidden());

        // Permitidos: PDF, .docx y audio.
        JsonNode pdf = read(upload(w.teacher(), chat, "Plan de estudio (v2).pdf", OfficeFiles.PDF, null)
                .andExpect(status().isCreated()));
        assertThat(pdf.get("scanStatus").asText()).isEqualTo("LIMPIO");
        assertThat(pdf.get("mediaType").asText()).isEqualTo("application/pdf");
        assertThat(pdf.toString()).doesNotContain("http").doesNotContain("chat/");
        read(upload(w.teacher(), chat, "guia.docx", OfficeFiles.docx(), null).andExpect(status().isCreated()));
        JsonNode audio = read(upload(w.student(), chat, "nota.ogg", OfficeFiles.OGG, 42).andExpect(status().isCreated()));
        assertThat(audio.get("durationSeconds").asInt()).isEqualTo(42);

        // El tipo del mensaje debe corresponder con el adjunto.
        send(w.teacher(), chat, "AUDIO", null, pdf.get("id").asText()).andExpect(status().isBadRequest());
        JsonNode doc = read(send(w.teacher(), chat, "DOCUMENTO", null, pdf.get("id").asText())
                .andExpect(status().isCreated()));
        assertThat(doc.get("attachment").get("id").asText()).isEqualTo(pdf.get("id").asText());
        send(w.student(), chat, "AUDIO", null, audio.get("id").asText()).andExpect(status().isCreated());

        // Descarga por URL prefirmada solo para participantes.
        JsonNode url = read(mvc.perform(get("/api/v1/chat-attachments/" + pdf.get("id").asText() + "/download-url")
                .header("Authorization", bearer(w.student().tokens()))).andExpect(status().isOk()));
        assertThat(url.get("url").asText()).contains("/api/v1/files/");
        assertThat(url.get("expiresAt").asText()).isNotBlank();
        mvc.perform(get("/api/v1/chat-attachments/" + pdf.get("id").asText() + "/download-url")
                .header("Authorization", bearer(stranger.tokens()))).andExpect(status().isForbidden());
    }

    @Test
    void studentContextRespectsTheMinimumNecessary() throws Exception {
        AttendanceWorld w = highRiskWorld(ISW);
        setting("ai.enabled", false);
        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of(ISW));
        analyze(counselor, w.studentId());
        relogin(w.teacher());
        UUID chat = open(w.teacher(), w.studentId());
        JsonNode context = read(mvc.perform(get("/api/v1/chats/" + chat + "/student-context")
                .header("Authorization", bearer(w.teacher().tokens()))).andExpect(status().isOk()));
        assertThat(context.at("/profile/anonCode").asText()).startsWith("EST-");
        assertThat(context.at("/profile/fullName").asText()).isNotBlank();
        JsonNode subject = context.get("sharedSubjects").get(0);
        assertThat(subject.get("countedAbsences").asInt()).isEqualTo(3);
        assertThat(subject.get("attendanceLevel").asText()).isEqualTo("CRITICO");
        assertThat(context.at("/series/risk")).isNotEmpty();
        assertThat(context.at("/series/absencesByWeek")).isNotEmpty();
        assertThat(context.at("/series/gradesBySubject")).hasSize(3);
        String text = context.toString().toLowerCase();
        assertThat(text).doesNotContain("estrato").doesNotContain("socioeconomic").doesNotContain("bienestar")
                .doesNotContain("wellbeing").doesNotContain("salud").doesNotContain("document");

        Actor stranger = staff(Set.of(Role.DOCENTE), Set.of());
        mvc.perform(get("/api/v1/chats/" + chat + "/student-context").header("Authorization", bearer(stranger.tokens())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/chats/" + chat + "/student-context").header("Authorization", bearer(w.student().tokens())))
                .andExpect(status().isForbidden());
    }

    @Test
    void appointmentLinkIsValidatedAndOnlyEditableByTheTeacher() throws Exception {
        AttendanceWorld w = world(ISW);
        for (String bad : new String[]{"javascript:alert(1)", "http://calendar.google.com/calendar/appointments/x",
                "https://evil.example.com/calendar.google.com", "https://calendar.google.com.evil.com/x",
                "https://usuario@calendar.google.com/x", "data:text/html,hola", "ftp://outlook.office.com/x"}) {
            putLink(w.teacher(), bad).andExpect(status().isBadRequest());
        }
        String good = "https://calendar.google.com/calendar/appointments/schedules/AcZssZ0";
        putLink(w.teacher(), good).andExpect(status().isOk()).andExpect(jsonPath("$.url").value(good));
        putLink(w.student(), good).andExpect(status().isForbidden());
        putLink(staff(Set.of(Role.CONSEJERO), Set.of(ISW)), good).andExpect(status().isForbidden());

        open(w.teacher(), w.studentId());
        JsonNode studentChats = read(mvc.perform(get("/api/v1/chats").header("Authorization", bearer(w.student().tokens())))
                .andExpect(status().isOk()));
        assertThat(studentChats.get(0).get("appointmentLink").asText()).isEqualTo(good);
        mvc.perform(delete("/api/v1/users/me/appointment-link").header("Authorization", bearer(w.teacher().tokens())))
                .andExpect(status().isNoContent());
    }

    @Test
    void chatTextNeverReachesTheAi() throws Exception {
        AttendanceWorld w = world(ISW);
        setting("ai.free-text.enabled", true);
        UUID chat = open(w.teacher(), w.studentId());
        send(w.teacher(), chat, "TEXTO", "Clave privada ZETA-4471 del chat", null).andExpect(status().isCreated());
        send(w.student(), chat, "TEXTO", "Respuesta confidencial OMEGA-9902", null).andExpect(status().isCreated());

        CLAUDE.willRespond(FakeClaude.ok(FakeClaude.textMessage("claude-haiku-5-5",
                FakeClaude.validExplanation("BAJO", null), "end_turn")));
        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of(ISW));
        analyze(counselor, w.studentId());
        assertThat(CLAUDE.requestCount()).isPositive();
        String everything = CLAUDE.requests().toString();
        assertThat(everything).doesNotContain("ZETA-4471").doesNotContain("OMEGA-9902");
    }

    private ResultActions putLink(Actor actor, String url) throws Exception {
        return mvc.perform(put("/api/v1/users/me/appointment-link").header("Authorization", bearer(actor.tokens()))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("url", url))));
    }
}
