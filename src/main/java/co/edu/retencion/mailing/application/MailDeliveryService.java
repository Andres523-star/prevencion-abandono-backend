package co.edu.retencion.mailing.application;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.mailing.domain.EmailOutbox;
import co.edu.retencion.mailing.domain.EmailOutboxRepository;
import co.edu.retencion.mailing.domain.EmailSuppression;
import co.edu.retencion.mailing.domain.EmailSuppressionRepository;

/**
 * Registro de entrega (consulta para administración) y manejo de rebotes. Un rebote marca el envío
 * como REBOTADO y suprime al destinatario (por su blind index) para no insistir con una dirección
 * inválida.
 */
@Service
public class MailDeliveryService {

    private final EmailOutboxRepository outbox;
    private final EmailSuppressionRepository suppressions;
    private final MailProperties properties;
    private final Clock clock;

    public MailDeliveryService(EmailOutboxRepository outbox, EmailSuppressionRepository suppressions,
                               MailProperties properties, Clock clock) {
        this.outbox = outbox;
        this.suppressions = suppressions;
        this.properties = properties;
        this.clock = clock;
    }

    /** Fila del registro de entrega. No incluye la dirección del destinatario. */
    public record DeliveryView(UUID id, UUID studentId, String template, EmailOutbox.Status status, int attempts,
                               Instant notBefore, Instant nextAttemptAt, Instant sentAt, String lastError,
                               Instant createdAt) {
        static DeliveryView of(EmailOutbox row) {
            return new DeliveryView(row.getId(), row.getStudentId(), row.getTemplate().name(), row.getStatus(),
                    row.getAttempts(), row.getNotBefore(), row.getNextAttemptAt(), row.getSentAt(), row.getLastError(),
                    row.getCreatedAt());
        }
    }

    @Transactional(readOnly = true)
    public List<DeliveryView> list(EmailOutbox.Status status, UUID studentId, int limit) {
        PageRequest page = PageRequest.of(0, Math.clamp(limit, 1, 500));
        List<EmailOutbox> rows;
        if (studentId != null) {
            rows = outbox.findByStudentIdOrderByCreatedAtDesc(studentId).stream()
                    .filter(r -> status == null || r.getStatus() == status).limit(page.getPageSize()).toList();
        } else if (status != null) {
            rows = outbox.findByStatusOrderByCreatedAtDesc(status, page);
        } else {
            rows = outbox.findAllByOrderByCreatedAtDesc(page);
        }
        return rows.stream().map(DeliveryView::of).toList();
    }

    /** Verifica la firma HMAC-SHA256 (hex) del cuerpo del webhook en tiempo constante. */
    public boolean validSignature(byte[] body, String signatureHex) {
        String secret = properties.webhookSecret();
        if (secret == null || secret.isBlank() || signatureHex == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(body);
            byte[] given = HexFormat.of().parseHex(signatureHex.trim().toLowerCase());
            return MessageDigest.isEqual(expected, given);
        } catch (IllegalArgumentException ex) {
            return false;
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Registra un rebote informado por el proveedor.
     *
     * @return envíos marcados como rebotados
     */
    @Transactional
    public int bounce(String providerMessageId, String reason) {
        Instant now = clock.instant();
        List<EmailOutbox> rows = outbox.findByProviderMessageId(providerMessageId);
        for (EmailOutbox row : rows) {
            row.bounced(reason == null ? "rebote" : reason);
            String index = row.getRecipientIndex();
            if (index != null && !suppressions.existsById(index)) {
                suppressions.save(new EmailSuppression(index, reason, now));
            }
        }
        return rows.size();
    }
}
