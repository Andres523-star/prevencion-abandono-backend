package co.edu.retencion.justifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.mailing.application.EmailSender;
import co.edu.retencion.mailing.application.EmailSender.EmailMessage;
import co.edu.retencion.mailing.application.MailDispatcher;
import co.edu.retencion.mailing.domain.EmailOutbox;
import co.edu.retencion.mailing.domain.EmailOutboxRepository;
import co.edu.retencion.mailing.domain.MailTemplate;
import co.edu.retencion.mailing.infrastructure.InMemoryEmailSender;
import co.edu.retencion.records.AttendanceLevel;
import co.edu.retencion.records.AttendanceThresholdReached;
import co.edu.retencion.records.SubjectLostByAbsences;
import co.edu.retencion.support.AttendanceWorld;
import co.edu.retencion.support.AttendanceWorld.Actor;
import co.edu.retencion.support.RecordsIntegrationTest;

/**
 * Criterio de aceptación de la Fase 3: docente marca falta → correo encolado → estudiante justifica
 * con evidencia → antivirus → docente aprueba/rechaza → contador correcto → evento de umbral.
 */
@RecordApplicationEvents
class AbsenceFlowIT extends RecordsIntegrationTest {

    /** Cadena de prueba EICAR (inofensiva; todos los antivirus la detectan). */
    static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";
    static final byte[] PDF = "%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\ntrailer << >>\n%%EOF\n"
            .getBytes(StandardCharsets.US_ASCII);
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

    @Autowired
    ApplicationEvents events;
    @Autowired
    EmailOutboxRepository outbox;
    @Autowired
    MailDispatcher dispatcher;
    @Autowired
    EmailSender sender;

    @AfterEach
    void clearMail() {
        ((InMemoryEmailSender) sender).clear();
    }

    @Test
    void fullAbsenceJustificationFlow() throws Exception {
        AttendanceWorld w = world(ISW);
        assertThat(w.pastSessions()).hasSizeGreaterThanOrEqualTo(4);

        // 1. El docente marca dos faltas (cada una dentro de su ventana de edición).
        UUID first = markAbsence(w, 0);
        UUID second = markAbsence(w, 1);
        JsonNode counter = counter(w);
        assertThat(counter.get("countedAbsences").asInt()).isEqualTo(2);
        assertThat(counter.get("level").asText()).isEqualTo("AVISO");
        assertThat(thresholds(w)).extracting(AttendanceThresholdReached::level).containsExactly(AttendanceLevel.AVISO);

        // 2. Correos encolados: salen solo cuando cierra la ventana de edición del docente.
        List<EmailOutbox> queued = outbox.findByStudentIdOrderByCreatedAtDesc(w.studentId());
        assertThat(queued).hasSize(2).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(EmailOutbox.Status.PENDIENTE);
            assertThat(row.getTemplate().isAbsenceNotice()).isTrue();
        });
        assertThat(queued).extracting(EmailOutbox::getNotBefore)
                .contains(w.sessionEnd(1).plus(Duration.ofHours(48)));

        // 3. El estudiante justifica la segunda falta.
        UUID justification = submit(w, second, "Cita médica de urgencia en el hospital");
        assertThat(counter(w).get("pending").asInt()).isEqualTo(1);

        // 4. Evidencias: EICAR dentro de un PDF → 422; extensión engañosa → 400; PDF válido → 201.
        byte[] infected = (new String(PDF, StandardCharsets.US_ASCII) + EICAR).getBytes(StandardCharsets.US_ASCII);
        upload(w.student(), justification, "certificado.pdf", infected).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("file_infected"));
        upload(w.student(), justification, "certificado.pdf", PNG).andExpect(status().isBadRequest());
        upload(w.student(), justification, "virus.exe", "MZ\u0090\u0000binario".getBytes(StandardCharsets.ISO_8859_1))
                .andExpect(status().isBadRequest());
        JsonNode evidence = read(upload(w.student(), justification, "certificado.pdf", PDF)
                .andExpect(status().isCreated()));
        assertThat(evidence.get("scanStatus").asText()).isEqualTo("LIMPIO");

        // 5. El docente ve la pendiente y la evidencia (URL prefirmada de corta duración).
        mvc.perform(get("/api/v1/justifications/pending").header("Authorization", bearer(w.teacher().tokens())))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(justification.toString()));
        mvc.perform(get("/api/v1/evidences/" + evidence.get("id").asText() + "/download-url")
                        .header("Authorization", bearer(w.teacher().tokens())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.url").isNotEmpty());

        // 6. Rechazar sin comentario no se permite; aprobar descuenta la falta.
        decide(w.teacher(), justification, false, null).andExpect(status().isBadRequest());
        JsonNode decision = read(decide(w.teacher(), justification, true, "Soporte válido").andExpect(status().isOk()));
        assertThat(decision.at("/justification/status").asText()).isEqualTo("APROBADA");
        assertThat(decision.at("/counter/countedAbsences").asInt()).isEqualTo(1);
        assertThat(decision.at("/counter/justified").asInt()).isEqualTo(1);
        assertThat(decision.at("/counter/level").asText()).isEqualTo("NINGUNO");
        assertThat(events.stream(JustificationDecided.class)).anyMatch(e -> e.justificationId().equals(justification));

        // 7. La primera falta se justifica y se rechaza (con comentario): sigue contando.
        UUID rejected = submit(w, first, "Problemas de transporte");
        decide(w.teacher(), rejected, false, "No adjuntó soporte").andExpect(status().isOk())
                .andExpect(jsonPath("$.counter.countedAbsences").value(1))
                .andExpect(jsonPath("$.counter.unjustified").value(1));

        // 8. Nuevas faltas: el nivel vuelve a subir (AVISO) y luego CRÍTICO, con sus eventos.
        markAbsence(w, 2);
        markAbsence(w, 3);
        assertThat(counter(w).get("countedAbsences").asInt()).isEqualTo(3);
        assertThat(thresholds(w)).extracting(AttendanceThresholdReached::level)
                .containsExactly(AttendanceLevel.AVISO, AttendanceLevel.AVISO, AttendanceLevel.CRITICO);
        assertThat(events.stream(SubjectLostByAbsences.class)).noneMatch(e -> e.studentId().equals(w.studentId()));

        // 9. Al cerrar la ventana de edición sale el correo; la falta aprobada ya no se avisa.
        at(w.sessionEnd(3).plus(Duration.ofHours(49)), w);
        dispatchAll();
        List<EmailMessage> mails = mailsTo(w);
        assertThat(mails).isNotEmpty();
        assertThat(outbox.findByDedupeKey("absence:" + second)).get()
                .extracting(EmailOutbox::getStatus).isEqualTo(EmailOutbox.Status.CANCELADO);
        assertThat(outbox.findByDedupeKey("absence:" + first)).get()
                .satisfies(row -> assertThat(row.getStatus()).isEqualTo(EmailOutbox.Status.ENVIADO));
        assertThat(outbox.findByDedupeKey("justification:" + justification)).get()
                .satisfies(row -> assertThat(row.getTemplate()).isEqualTo(MailTemplate.JUSTIFICACION_APROBADA));
    }

    @Test
    void onlyTheGroupTeacherCanDecide() throws Exception {
        AttendanceWorld w = world(ISW);
        UUID record = markAbsence(w, 0);
        UUID justification = submit(w, record, "Calamidad familiar");

        Actor otherTeacher = staff(Set.of(Role.DOCENTE), Set.of());
        Actor coordinator = staff(Set.of(Role.COORDINADOR), Set.of(ISW));
        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of(ISW));
        for (Actor actor : List.of(otherTeacher, coordinator, counselor, w.admin())) {
            decide(actor, justification, true, "ok").andExpect(status().isForbidden());
        }
        // Otro docente tampoco ve el detalle ni la bandeja de este grupo.
        mvc.perform(get("/api/v1/justifications/" + justification)
                        .header("Authorization", bearer(otherTeacher.tokens())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/justifications/pending").header("Authorization", bearer(otherTeacher.tokens())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        // Y el estudiante no puede aprobar su propia justificación.
        decide(w.student(), justification, true, "ok").andExpect(status().isForbidden());

        decide(w.teacher(), justification, true, "Soporte válido").andExpect(status().isOk());
        assertThat(counter(w).get("justified").asInt()).isEqualTo(1);
    }

    @Test
    void pendingJustificationsMoveToTheNewTeacher() throws Exception {
        AttendanceWorld w = world(ISW);
        UUID justification = submit(w, markAbsence(w, 0), "Cita médica");
        Actor newTeacher = staff(Set.of(Role.DOCENTE), Set.of());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/groups/" + w.groupId() + "/teacher")
                        .header("Authorization", bearer(w.admin().tokens()))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("teacherUserId", newTeacher.userId()))))
                .andExpect(status().isOk());
        relogin(w.teacher(), newTeacher);

        decide(w.teacher(), justification, true, "ok").andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/justifications/pending").header("Authorization", bearer(newTeacher.tokens())))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(justification.toString()));
        decide(newTeacher, justification, true, "ok").andExpect(status().isOk());
    }

    @Test
    void retractedAbsenceCancelsTheEmailAndUnjustifiedAbsencesExpire() throws Exception {
        AttendanceWorld w = world(ISW);
        UUID record = markAbsence(w, 0);
        // El docente corrige su error dentro de la ventana: el aviso se cancela.
        mark(w.teacher().tokens(), w.sessionId(0), w.enrollmentId(), "PRESENTE", null).andExpect(status().isOk());
        assertThat(outbox.findByDedupeKey("absence:" + record)).get()
                .extracting(EmailOutbox::getStatus).isEqualTo(EmailOutbox.Status.CANCELADO);

        UUID unjustified = markAbsence(w, 1);
        at(w.sessionEnd(1).plus(Duration.ofDays(10)), w);
        postJson("/api/v1/justifications/expire", w.admin().tokens(), Map.of()).andExpect(status().isOk());
        JsonNode history = read(mvc.perform(get("/api/v1/students/" + w.studentId() + "/attendance")
                .header("Authorization", bearer(w.admin().tokens()))).andExpect(status().isOk()));
        assertThat(history.findValues("recordId").stream().map(JsonNode::asText)).contains(unjustified.toString());
        for (JsonNode row : history) {
            if (row.get("recordId").asText().equals(unjustified.toString())) {
                assertThat(row.get("justification").asText()).isEqualTo("VENCIDA");
            }
        }
        // Vencida: ya no se puede justificar.
        postJson("/api/v1/justifications", w.student().tokens(), Map.of("attendanceRecordId", unjustified,
                "category", "OTRO", "description", "tarde")).andExpect(status().isConflict());
        assertThat(counter(w).get("unjustified").asInt()).isEqualTo(1);
    }

    /** La base de datos es compartida: se despacha hasta vaciar lo vencido (incluye filas de otras pruebas). */
    private void dispatchAll() {
        for (int i = 0; i < 50 && dispatcher.dispatch().claimed() > 0; i++) {
            // siguiente lote
        }
    }

    private List<AttendanceThresholdReached> thresholds(AttendanceWorld w) {
        return events.stream(AttendanceThresholdReached.class)
                .filter(e -> e.enrollmentId().equals(w.enrollmentId())).toList();
    }

    private List<EmailMessage> mailsTo(AttendanceWorld w) {
        return ((InMemoryEmailSender) sender).sent().stream().filter(m -> m.to().equals(w.studentEmail())).toList();
    }

    private UUID submit(AttendanceWorld w, UUID recordId, String description) throws Exception {
        return UUID.fromString(read(postJson("/api/v1/justifications", w.student().tokens(), Map.of(
                "attendanceRecordId", recordId, "category", "SALUD", "description", description))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDIENTE"))).get("id").asText());
    }

    private ResultActions upload(Actor actor, UUID justification, String fileName, byte[] content) throws Exception {
        return mvc.perform(multipart("/api/v1/justifications/" + justification + "/evidences")
                .file(new MockMultipartFile("file", fileName, "application/octet-stream", content))
                .header("Authorization", bearer(actor.tokens())));
    }

    private ResultActions decide(Actor actor, UUID justification, boolean approved, String comment) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("approved", approved);
        body.put("comment", comment);
        return postJson("/api/v1/justifications/" + justification + "/decision", actor.tokens(), body);
    }
}
