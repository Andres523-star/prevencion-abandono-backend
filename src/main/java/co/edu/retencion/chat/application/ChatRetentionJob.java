package co.edu.retencion.chat.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.chat.domain.ChatAttachmentRepository;
import co.edu.retencion.chat.domain.ChatMessage;
import co.edu.retencion.chat.domain.ChatMessageRepository;
import co.edu.retencion.common.files.ObjectStorage;
import co.edu.retencion.settings.SettingsProvider;

/**
 * Retención configurable del chat ({@code chat.retention-days}): borra el contenido de los mensajes
 * vencidos y sus adjuntos del almacenamiento; el registro (quién, cuándo) queda para la auditoría.
 */
@Component
public class ChatRetentionJob {

    private final ChatMessageRepository messages;
    private final ChatAttachmentRepository attachments;
    private final ObjectStorage storage;
    private final SettingsProvider settings;
    private final Clock clock;

    public ChatRetentionJob(ChatMessageRepository messages, ChatAttachmentRepository attachments, ObjectStorage storage,
                            SettingsProvider settings, Clock clock) {
        this.messages = messages;
        this.attachments = attachments;
        this.storage = storage;
        this.settings = settings;
        this.clock = clock;
    }

    @Scheduled(cron = "0 15 2 * * *", zone = "America/Bogota")
    @Transactional
    public int purgeExpired() {
        Instant now = clock.instant();
        Instant before = now.minus(Duration.ofDays(Math.max(1, settings.getInt("chat.retention-days", 730))));
        List<ChatMessage> expired = messages.findExpired(before, Limit.of(1000));
        for (ChatMessage m : expired) {
            if (m.getAttachmentId() != null) {
                attachments.findById(m.getAttachmentId()).ifPresent(a -> {
                    storage.delete(a.getObjectKey());
                    a.delete(now);
                    attachments.save(a);
                });
            }
            m.purge(now);
            messages.save(m);
        }
        return expired.size();
    }
}
