package co.edu.retencion.mailing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class MailingDomainTest {

    static final Instant NOW = Instant.parse("2026-10-05T17:00:00Z");

    @Test
    void failuresBackOffExponentiallyUntilTheLimit() {
        EmailOutbox row = row();
        row.failed("http_503", false, 4, Duration.ofMinutes(1), Duration.ofMinutes(3), NOW);
        assertThat(row.getStatus()).isEqualTo(EmailOutbox.Status.PENDIENTE);
        assertThat(row.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        row.failed("http_503", false, 4, Duration.ofMinutes(1), Duration.ofMinutes(3), NOW);
        assertThat(row.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(2)));
        row.failed("http_503", false, 4, Duration.ofMinutes(1), Duration.ofMinutes(3), NOW);
        assertThat(row.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(3)));
        row.failed("http_503", false, 4, Duration.ofMinutes(1), Duration.ofMinutes(3), NOW);
        assertThat(row.getStatus()).isEqualTo(EmailOutbox.Status.FALLIDO);
        assertThat(row.getAttempts()).isEqualTo(4);
    }

    @Test
    void permanentFailuresAreNotRetried() {
        EmailOutbox row = row();
        row.failed("x".repeat(400), true, 8, Duration.ofMinutes(1), Duration.ofHours(1), NOW);
        assertThat(row.getStatus()).isEqualTo(EmailOutbox.Status.FALLIDO);
        assertThat(row.getLastError()).hasSize(300);
    }

    @Test
    void cancelledNoticesCanBeRescheduledButSentOnesCannot() {
        EmailOutbox row = row();
        assertThat(row.cancel("corregida")).isTrue();
        assertThat(row.reschedule(Map.of("recordId", "r"), NOW.plusSeconds(60))).isTrue();
        assertThat(row.getStatus()).isEqualTo(EmailOutbox.Status.PENDIENTE);
        row.sent(MailTemplate.PRIMERA_FALTA, "idx", "provider-1", NOW);
        assertThat(row.reschedule(Map.of(), NOW)).isFalse();
        assertThat(row.cancel("tarde")).isFalse();
        row.bounced("mailbox_not_found");
        assertThat(row.getStatus()).isEqualTo(EmailOutbox.Status.REBOTADO);
    }

    @Test
    void templatesNeverMentionRiskOrHealthInTheSubject() {
        for (MailTemplate template : MailTemplate.values()) {
            String subject = template.subject("Anatomía").toLowerCase();
            assertThat(subject).doesNotContain("riesgo").doesNotContain("salud").doesNotContain("psicol");
            assertThat(template.view()).startsWith("mail/");
        }
        assertThat(MailTemplate.CRITICO.isAbsenceNotice()).isTrue();
        assertThat(MailTemplate.JUSTIFICACION_APROBADA.isAbsenceNotice()).isFalse();
    }

    @Test
    void suppressionReasonIsBounded() {
        assertThat(new EmailSuppression("idx", null, NOW).getReason()).isEqualTo("rebote");
        assertThat(new EmailSuppression("idx", "y".repeat(150), NOW).getReason()).hasSize(100);
    }

    private static EmailOutbox row() {
        return EmailOutbox.schedule(UUID.randomUUID(), MailTemplate.FALTA_REGISTRADA, "absence:" + UUID.randomUUID(),
                Map.of("digest", false), NOW, NOW);
    }
}
