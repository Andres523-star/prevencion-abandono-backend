package co.edu.retencion.chat.application;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.chat.application.ChatViews.AttachmentView;
import co.edu.retencion.chat.domain.ChatAttachment;
import co.edu.retencion.chat.domain.ChatAttachmentRepository;
import co.edu.retencion.chat.domain.ChatFileTypes;
import co.edu.retencion.chat.domain.ChatMessageRepository;
import co.edu.retencion.chat.domain.Conversation;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.files.MalwareScanner;
import co.edu.retencion.common.files.ObjectStorage;
import co.edu.retencion.common.security.AuthenticatedUser;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.settings.SettingsProvider;

/**
 * Adjuntos del chat: se suben ANTES de enviar el mensaje y la respuesta es un {@code attachmentId}
 * (nunca una URL pública). Validación por magic bytes, tamaño y duración máximos, y antivirus antes
 * de guardar: un archivo infectado nunca llega al almacenamiento.
 *
 * <p>La duración del audio la declara el cliente (la grabadora del navegador la conoce) y se valida
 * contra el máximo; el tamaño máximo acota además cualquier declaración falsa.
 */
@Service
public class ChatAttachmentService {

    static final Duration DOWNLOAD_TTL = Duration.ofMinutes(5);
    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentService.class);

    private final ChatAttachmentRepository attachments;
    private final ChatMessageRepository messages;
    private final ChatService chat;
    private final ObjectStorage storage;
    private final MalwareScanner scanner;
    private final SettingsProvider settings;
    private final Clock clock;

    public ChatAttachmentService(ChatAttachmentRepository attachments, ChatMessageRepository messages, ChatService chat,
                                 ObjectStorage storage, MalwareScanner scanner, SettingsProvider settings, Clock clock) {
        this.attachments = attachments;
        this.messages = messages;
        this.chat = chat;
        this.storage = storage;
        this.scanner = scanner;
        this.settings = settings;
        this.clock = clock;
    }

    public record DownloadView(URI url, java.time.Instant expiresAt) {
    }

    @Transactional
    public AttachmentView upload(AuthenticatedUser actor, UUID conversationId, String fileName, byte[] content,
                                 Integer durationSeconds) {
        Conversation conversation = chat.requireParticipant(actor.userId(), conversationId);
        chat.requireWritable(conversation);
        long maxBytes = settings.getLong("chat.attachment-max-mb", 10) * 1024 * 1024;
        ChatFileTypes.Kind kind = ChatFileTypes.requireAllowed(fileName, content, maxBytes);
        Integer duration = null;
        if (kind.messageType() == co.edu.retencion.chat.domain.ChatMessage.Type.AUDIO) {
            int max = settings.getInt("chat.audio-max-seconds", 300);
            if (durationSeconds == null || durationSeconds < 1 || durationSeconds > max) {
                throw DomainException.badRequest("La nota de audio debe durar entre 1 y " + max + " segundos");
            }
            duration = durationSeconds;
        }
        MalwareScanner.ScanResult scan = scanner.scan(content);
        if (scan.verdict() == MalwareScanner.Verdict.INFECTED) {
            log.warn("Adjunto de chat rechazado por el antivirus (firma {})", scan.signature());
            throw new DomainException(ChatProblems.FILE_INFECTED, "El archivo fue rechazado por el antivirus");
        }
        ChatAttachment attachment = ChatAttachment.uploaded(conversationId, actor.userId(), ChatFileTypes.safeName(fileName),
                kind.extension(), kind.mediaType(), content.length, duration, sha256(content),
                scan.verdict() == MalwareScanner.Verdict.CLEAN ? ChatAttachment.ScanStatus.LIMPIO
                        : ChatAttachment.ScanStatus.ERROR, clock.instant());
        storage.put(attachment.getObjectKey(), content, kind.mediaType());
        return AttachmentView.of(attachments.save(attachment));
    }

    /** URL prefirmada de 5 minutos, solo para participantes (o ADMIN), con el adjunto limpio. */
    @Transactional(readOnly = true)
    public DownloadView downloadUrl(AuthenticatedUser actor, UUID attachmentId) {
        ChatAttachment a = attachments.findById(attachmentId).filter(x -> x.getDeletedAt() == null)
                .orElseThrow(() -> DomainException.notFound("Adjunto no encontrado"));
        boolean participant = chat.participates(actor.userId(), a.getConversationId());
        if (!participant && !actor.hasRole(Role.ADMIN)) {
            throw new AccessDeniedException("No participas en esta conversación");
        }
        boolean sentAndVisible = a.getMessageId() != null
                && messages.findById(a.getMessageId()).map(m -> m.visible()).orElse(false);
        if (!sentAndVisible && !a.getUploaderId().equals(actor.userId())) {
            throw DomainException.notFound("Adjunto no encontrado");
        }
        if (a.getScanStatus() != ChatAttachment.ScanStatus.LIMPIO) {
            throw DomainException.conflict("El adjunto no está disponible");
        }
        return new DownloadView(storage.presignedDownloadUrl(a.getObjectKey(), DOWNLOAD_TTL, a.getOriginalName()),
                clock.instant().plus(DOWNLOAD_TTL));
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
