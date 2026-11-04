package co.edu.retencion.mailing.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import co.edu.retencion.mailing.application.EmailSender.EmailDeliveryException;
import co.edu.retencion.mailing.application.EmailSender.EmailMessage;
import co.edu.retencion.mailing.domain.EmailOutbox;
import co.edu.retencion.mailing.domain.EmailOutboxRepository;
import co.edu.retencion.mailing.domain.EmailSuppressionRepository;
import co.edu.retencion.mailing.domain.MailTemplate;
import co.edu.retencion.records.AbsenceInfo;
import co.edu.retencion.records.AttendanceLedger;
import co.edu.retencion.records.AttendanceLevel;
import co.edu.retencion.records.AttendanceStatus;
import co.edu.retencion.records.CounterInfo;
import co.edu.retencion.records.JustificationState;
import co.edu.retencion.students.StudentRegistry;

/**
 * Despachador del outbox. Reclama filas vencidas con {@code FOR UPDATE SKIP LOCKED} (varias
 * instancias pueden despachar sin pisarse), vuelve a comprobar la falta y el contador al momento
 * de enviar (el aviso refleja el estado real, no el del evento), resuelve la dirección y envía.
 * Entrega al menos una vez: si la instancia cae tras enviar y antes de confirmar, la fila se
 * reintenta al vencer el lease.
 */
@Service
public class MailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MailDispatcher.class);

    private static final String CLAIM_SQL = """
            UPDATE email_outbox SET status = 'ENVIANDO', next_attempt_at = ?, version = version + 1
            WHERE id IN (SELECT id FROM email_outbox
                         WHERE status IN ('PENDIENTE', 'ENVIANDO') AND next_attempt_at <= ?
                         ORDER BY next_attempt_at
                         LIMIT ?
                         FOR UPDATE SKIP LOCKED)
            RETURNING id""";

    private final EmailOutboxRepository outbox;
    private final EmailSuppressionRepository suppressions;
    private final AttendanceLedger ledger;
    private final StudentRegistry students;
    private final MailComposer composer;
    private final EmailSender sender;
    private final MailProperties properties;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final Clock clock;

    public MailDispatcher(EmailOutboxRepository outbox, EmailSuppressionRepository suppressions,
                          AttendanceLedger ledger, StudentRegistry students, MailComposer composer, EmailSender sender,
                          MailProperties properties, JdbcTemplate jdbc, TransactionTemplate tx, MeterRegistry meters,
                          Clock clock) {
        this.outbox = outbox;
        this.suppressions = suppressions;
        this.ledger = ledger;
        this.students = students;
        this.composer = composer;
        this.sender = sender;
        this.properties = properties;
        this.jdbc = jdbc;
        this.tx = tx;
        this.meters = meters;
        this.clock = clock;
    }

    /** Resultado de un ciclo (para el endpoint de administración y los tests). */
    public record DispatchReport(int claimed, int sent, int cancelled, int failed) {
    }

    @Scheduled(fixedDelayString = "${app.mail.dispatch-interval-ms:30000}",
            initialDelayString = "${app.mail.dispatch-initial-delay-ms:30000}")
    void scheduled() {
        if (properties.dispatchEnabled()) {
            dispatch();
        }
    }

    /** Ejecuta un ciclo de despacho. */
    public DispatchReport dispatch() {
        List<UUID> claimed = claim();
        Map<UUID, List<EmailOutbox>> digests = new LinkedHashMap<>();
        Counts counts = new Counts();
        for (UUID id : claimed) {
            EmailOutbox row = outbox.findById(id).orElse(null);
            if (row == null) {
                continue;
            }
            if (row.isDigest() && row.getTemplate().isAbsenceNotice()) {
                digests.computeIfAbsent(row.getStudentId(), k -> new ArrayList<>()).add(row);
            } else {
                tx.executeWithoutResult(s -> process(List.of(row.getId()), false, counts));
            }
        }
        digests.values().forEach(rows -> tx.executeWithoutResult(
                s -> process(rows.stream().map(EmailOutbox::getId).toList(), true, counts)));
        return new DispatchReport(claimed.size(), counts.sent, counts.cancelled, counts.failed);
    }

    private List<UUID> claim() {
        Instant now = clock.instant();
        return tx.execute(s -> jdbc.queryForList(CLAIM_SQL, UUID.class, Timestamp.from(now.plus(properties.lease())),
                Timestamp.from(now), properties.batchSize()));
    }

    /** Una fila (o un grupo de filas del mismo estudiante en modo resumen) → un correo. */
    private void process(List<UUID> ids, boolean digest, Counts counts) {
        Instant now = clock.instant();
        List<EmailOutbox> rows = outbox.findAllById(ids);
        List<Item> items = new ArrayList<>();
        for (EmailOutbox row : rows) {
            Optional<Item> item = describe(row);
            if (item.isEmpty()) {
                row.cancel("La falta ya no aplica al momento de enviar");
                counts.cancelled++;
            } else {
                items.add(item.get());
            }
        }
        if (items.isEmpty()) {
            return;
        }
        UUID studentId = items.getFirst().row().getStudentId();
        Optional<StudentRegistry.Contact> contact = students.notificationContact(studentId);
        if (contact.isEmpty()) {
            items.forEach(i -> i.row().noRecipient());
            counts.failed += items.size();
            return;
        }
        String recipientIndex = contact.get().emailBlindIndex();
        if (recipientIndex != null && suppressions.existsById(recipientIndex)) {
            items.forEach(i -> i.row().suppressed(recipientIndex));
            counts.failed += items.size();
            return;
        }

        MailTemplate template = digest ? MailTemplate.RESUMEN_DIARIO : items.getFirst().template();
        MailComposer.Composed composed = digest
                ? composer.compose(template, null, Map.of("items", items.stream().map(Item::variables).toList()))
                : composer.compose(template, items.getFirst().subjectName(), items.getFirst().variables());
        String correlationId = items.getFirst().row().getId().toString();
        try {
            String providerId = sender.send(new EmailMessage(contact.get().email(), composed.subject(), composed.html(),
                    correlationId));
            items.forEach(i -> i.row().sent(digest ? MailTemplate.RESUMEN_DIARIO : i.template(), recipientIndex,
                    providerId, now));
            counts.sent += items.size();
            meters.counter("retencion.mail.sent", "template", template.name(), "provider", sender.name()).increment();
        } catch (EmailDeliveryException ex) {
            items.forEach(i -> i.row().failed(ex.getMessage(), ex.isPermanent(), properties.maxAttempts(),
                    properties.backoffBase(), properties.backoffMax(), now));
            counts.failed += items.size();
            meters.counter("retencion.mail.failed", "provider", sender.name()).increment();
            log.warn("Envío de correo fallido (outbox {}): {}", correlationId, ex.getMessage());
        } catch (RuntimeException ex) {
            items.forEach(i -> i.row().failed(ex.getClass().getSimpleName(), false, properties.maxAttempts(),
                    properties.backoffBase(), properties.backoffMax(), now));
            counts.failed += items.size();
            meters.counter("retencion.mail.failed", "provider", sender.name()).increment();
            log.warn("Error inesperado enviando correo (outbox {}): {}", correlationId, ex.getClass().getSimpleName());
        }
    }

    /**
     * Revalida la fila y arma sus variables. Un aviso de falta se cancela si la falta se corrigió o
     * el docente ya aprobó la justificación.
     */
    private Optional<Item> describe(EmailOutbox row) {
        UUID recordId = row.payloadUuid("recordId");
        UUID enrollmentId = row.payloadUuid("enrollmentId");
        Optional<AbsenceInfo> absence = recordId == null ? Optional.empty() : ledger.findRecord(recordId);
        Optional<CounterInfo> counter = enrollmentId == null ? Optional.empty() : ledger.counter(enrollmentId);
        String subjectName = row.payloadString("subjectName");

        Map<String, Object> vars = new HashMap<>();
        vars.put("materia", subjectName);
        vars.put("fecha", MailComposer.date(row.payloadString("sessionDate")));
        counter.ifPresent(c -> {
            vars.put("faltas", c.countedAbsences());
            vars.put("limite", c.maxAbsences());
            vars.put("restantes", Math.max(0, c.maxAbsences() - c.countedAbsences()));
        });

        MailTemplate template = row.getTemplate();
        if (template.isAbsenceNotice()) {
            if (absence.isEmpty() || absence.get().status() != AttendanceStatus.FALTA
                    || absence.get().justification() == JustificationState.APROBADA || counter.isEmpty()) {
                return Optional.empty();
            }
            vars.put("fechaLimite", MailComposer.date(row.payloadString("justificationDeadline")));
            template = absenceTemplate(counter.get());
        }
        return Optional.of(new Item(row, template, subjectName, vars));
    }

    static MailTemplate absenceTemplate(CounterInfo counter) {
        AttendanceLevel level = counter.level();
        return switch (level) {
            case PERDIDA -> MailTemplate.PERDIDA;
            case CRITICO -> MailTemplate.CRITICO;
            case AVISO -> MailTemplate.AVISO;
            case NINGUNO -> counter.countedAbsences() <= 1 ? MailTemplate.PRIMERA_FALTA : MailTemplate.FALTA_REGISTRADA;
        };
    }

    private record Item(EmailOutbox row, MailTemplate template, String subjectName, Map<String, Object> variables) {
    }

    private static final class Counts {
        int sent;
        int cancelled;
        int failed;
    }
}
