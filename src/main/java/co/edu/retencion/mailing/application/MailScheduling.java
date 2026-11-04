package co.edu.retencion.mailing.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import co.edu.retencion.common.time.TimeConfig;
import co.edu.retencion.justifications.JustificationDecided;
import co.edu.retencion.mailing.domain.EmailOutbox;
import co.edu.retencion.mailing.domain.EmailOutboxRepository;
import co.edu.retencion.mailing.domain.MailTemplate;
import co.edu.retencion.records.AbsenceRecorded;
import co.edu.retencion.records.AbsenceRetracted;
import co.edu.retencion.settings.SettingsProvider;

/**
 * Convierte eventos de dominio en filas del outbox. El aviso de falta no sale de inmediato: espera a
 * que cierre la ventana de edición del docente (o al resumen diario) para no avisar por errores de
 * digitación; si la falta se corrige antes, el aviso se cancela.
 */
@Component
class MailScheduling {

    static final String MODE_KEY = "mail.absence-mode";
    static final String DIGEST_HOUR_KEY = "mail.digest-hour";
    static final String DAILY_DIGEST = "DAILY_DIGEST";

    private final EmailOutboxRepository outbox;
    private final SettingsProvider settings;
    private final Clock clock;

    MailScheduling(EmailOutboxRepository outbox, SettingsProvider settings, Clock clock) {
        this.outbox = outbox;
        this.settings = settings;
        this.clock = clock;
    }

    @ApplicationModuleListener
    void on(AbsenceRecorded event) {
        Instant now = clock.instant();
        boolean digest = DAILY_DIGEST.equals(settings.getString(MODE_KEY, "AFTER_EDIT_WINDOW"));
        Instant notBefore = notBefore(event.editableUntil(), digest, now);
        Map<String, Object> payload = new HashMap<>();
        payload.put("recordId", event.recordId().toString());
        payload.put("enrollmentId", event.enrollmentId().toString());
        payload.put("groupId", event.groupId().toString());
        payload.put("subjectName", event.subjectName());
        payload.put("sessionDate", event.sessionDate().toString());
        payload.put("justificationDeadline",
                event.justificationDeadline() == null ? null : event.justificationDeadline().toString());
        payload.put("digest", digest);

        String key = absenceKey(event.recordId().toString());
        outbox.findByDedupeKey(key).ifPresentOrElse(
                existing -> existing.reschedule(payload, notBefore),
                () -> outbox.save(EmailOutbox.schedule(event.studentId(), MailTemplate.FALTA_REGISTRADA, key, payload,
                        notBefore, now)));
    }

    @ApplicationModuleListener
    void on(AbsenceRetracted event) {
        outbox.findByDedupeKey(absenceKey(event.recordId().toString()))
                .ifPresent(row -> row.cancel("La falta fue corregida antes del envío"));
    }

    @ApplicationModuleListener
    void on(JustificationDecided event) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("recordId", event.recordId().toString());
        payload.put("enrollmentId", event.enrollmentId().toString());
        payload.put("subjectName", event.subjectName());
        payload.put("sessionDate", event.sessionDate() == null ? null : event.sessionDate().toString());
        String key = "justification:" + event.justificationId();
        if (outbox.findByDedupeKey(key).isEmpty()) {
            Instant now = clock.instant();
            outbox.save(EmailOutbox.schedule(event.studentId(), event.approved()
                    ? MailTemplate.JUSTIFICACION_APROBADA : MailTemplate.JUSTIFICACION_RECHAZADA, key, payload, now, now));
        }
    }

    /** Envío tras la ventana de edición o, en modo resumen, a la siguiente hora de resumen (Bogotá). */
    Instant notBefore(Instant editableUntil, boolean digest, Instant now) {
        Instant base = editableUntil == null || editableUntil.isBefore(now) ? now : editableUntil;
        if (!digest) {
            return base;
        }
        int hour = Math.clamp(settings.getInt(DIGEST_HOUR_KEY, 7), 0, 23);
        ZonedDateTime local = base.atZone(TimeConfig.BUSINESS_ZONE);
        ZonedDateTime candidate = local.toLocalDate().atTime(hour, 0).atZone(TimeConfig.BUSINESS_ZONE);
        return (candidate.isAfter(local) ? candidate : candidate.plusDays(1)).toInstant();
    }

    static String absenceKey(String recordId) {
        return "absence:" + recordId;
    }
}
