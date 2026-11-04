package co.edu.retencion.mailing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import co.edu.retencion.mailing.application.EmailSender;
import co.edu.retencion.mailing.application.EmailSender.EmailMessage;
import co.edu.retencion.mailing.application.MailDispatcher;
import co.edu.retencion.mailing.domain.EmailOutbox;
import co.edu.retencion.mailing.domain.EmailOutboxRepository;
import co.edu.retencion.mailing.domain.EmailSuppressionRepository;
import co.edu.retencion.mailing.infrastructure.InMemoryEmailSender;
import co.edu.retencion.support.AttendanceWorld;
import co.edu.retencion.support.RecordsIntegrationTest;

/** Correo de avisos de faltas: contenido mínimo, reintentos, rebotes y supresión. */
class AbsenceMailIT extends RecordsIntegrationTest {

    /** Palabras que nunca deben aparecer en un correo al estudiante. */
    static final List<String> FORBIDDEN = List.of("riesgo", "psicol", "salud", "bienestar", "socioecon", "estrato",
            "probabilidad", "deserci", "Cita médica", "Estudiante de Prueba", "EST-");

    @Autowired
    MailDispatcher dispatcher;
    @Autowired
    EmailSender sender;
    @Autowired
    EmailOutboxRepository outbox;
    @Autowired
    EmailSuppressionRepository suppressions;

    @AfterEach
    void clearMail() {
        memory().clear();
    }

    @Test
    void absenceEmailContainsOnlyAcademicFacts() throws Exception {
        AttendanceWorld w = world(ISW);
        UUID record = markAbsence(w, 0);
        // Justificación con categoría y descripción de salud: no deben llegar al correo.
        postJson("/api/v1/justifications", w.student().tokens(), Map.of("attendanceRecordId", record,
                "category", "SALUD", "description", "Cita médica con psicología")).andExpect(status().isCreated());

        // Antes de cerrar la ventana de edición no sale nada.
        dispatchAll();
        assertThat(mailsTo(w)).isEmpty();

        at(w.sessionEnd(0).plus(Duration.ofHours(49)), w);
        dispatchAll();
        List<EmailMessage> mails = mailsTo(w);
        assertThat(mails).hasSize(1);
        EmailMessage mail = mails.getFirst();
        String content = mail.subject() + "\n" + mail.html();
        assertThat(content).contains(w.subjectName())
                .contains(w.sessionDate(0).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")))
                .contains("<strong>1</strong> de <strong>4</strong>")
                .contains("https://portal.universidad.edu.co");
        for (String word : FORBIDDEN) {
            assertThat(content.toLowerCase()).as("el correo no debe contener '%s'", word)
                    .doesNotContain(word.toLowerCase());
        }
        assertThat(content).doesNotContain(w.studentId().toString());
        assertThat(mail.toString()).doesNotContain(w.studentEmail());

        // El registro de entrega no guarda la dirección.
        EmailOutbox row = outbox.findByDedupeKey("absence:" + record).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(EmailOutbox.Status.ENVIADO);
        assertThat(row.getPayload().toString()).doesNotContain(w.studentEmail());
    }

    @Test
    void transientFailuresAreRetriedWithBackoffAndBouncesSuppressTheRecipient() throws Exception {
        AttendanceWorld w = world(ISW);
        UUID record = markAbsence(w, 0);
        at(w.sessionEnd(0).plus(Duration.ofHours(49)), w);
        dispatchAll();
        memory().clear();

        UUID second = markAbsence(w, 1);
        at(w.sessionEnd(1).plus(Duration.ofHours(49)), w);
        memory().failNext(1000);
        dispatchAll();
        EmailOutbox failed = outbox.findByDedupeKey("absence:" + second).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(EmailOutbox.Status.PENDIENTE);
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getNextAttemptAt()).isAfter(clock.instant());

        memory().clear();
        clock.advance(Duration.ofMinutes(2));
        dispatchAll();
        assertThat(outbox.findByDedupeKey("absence:" + second).orElseThrow().getStatus())
                .isEqualTo(EmailOutbox.Status.ENVIADO);

        // Rebote: firma inválida → 401; firma válida → REBOTADO y destinatario suprimido.
        EmailOutbox sent = outbox.findByDedupeKey("absence:" + record).orElseThrow();
        String body = json.writeValueAsString(Map.of("providerMessageId", sent.getProviderMessageId(),
                "reason", "mailbox_not_found"));
        mvc.perform(post("/api/v1/mail/bounces").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Mail-Signature", "00")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/mail/bounces").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Mail-Signature", sign(body))).andExpect(status().isNoContent());
        assertThat(outbox.findByDedupeKey("absence:" + record).orElseThrow().getStatus())
                .isEqualTo(EmailOutbox.Status.REBOTADO);
        assertThat(suppressions.existsById(sent.getRecipientIndex())).isTrue();

        UUID third = markAbsence(w, 2);
        at(w.sessionEnd(2).plus(Duration.ofHours(49)), w);
        memory().clear();
        dispatchAll();
        assertThat(outbox.findByDedupeKey("absence:" + third).orElseThrow().getStatus())
                .isEqualTo(EmailOutbox.Status.SUPRIMIDO);
        assertThat(mailsTo(w)).isEmpty();
    }

    @Test
    void deliveryLogIsAdminOnly() throws Exception {
        AttendanceWorld w = world(ISW);
        mvcGet("/api/v1/mail/outbox?studentId=" + w.studentId(), w.admin()).andExpect(status().isOk());
        mvcGet("/api/v1/mail/outbox", w.teacher()).andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.ResultActions mvcGet(String path, AttendanceWorld.Actor actor)
            throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .header("Authorization", bearer(actor.tokens())));
    }

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-webhook-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private void dispatchAll() {
        for (int i = 0; i < 50 && dispatcher.dispatch().claimed() > 0; i++) {
            // siguiente lote
        }
    }

    private InMemoryEmailSender memory() {
        return (InMemoryEmailSender) sender;
    }

    private List<EmailMessage> mailsTo(AttendanceWorld w) {
        return memory().sent().stream().filter(m -> m.to().equals(w.studentEmail())).toList();
    }
}
