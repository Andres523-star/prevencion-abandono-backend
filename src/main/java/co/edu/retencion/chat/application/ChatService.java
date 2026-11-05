package co.edu.retencion.chat.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import co.edu.retencion.chat.ChatMessageSent;
import co.edu.retencion.chat.application.ChatViews.AttachmentView;
import co.edu.retencion.chat.application.ChatViews.ChatSignal;
import co.edu.retencion.chat.application.ChatViews.ConversationView;
import co.edu.retencion.chat.application.ChatViews.MessageView;
import co.edu.retencion.chat.application.ChatViews.ReportView;
import co.edu.retencion.chat.domain.ChatAttachment;
import co.edu.retencion.chat.domain.ChatAttachmentRepository;
import co.edu.retencion.chat.domain.ChatMessage;
import co.edu.retencion.chat.domain.ChatMessageRepository;
import co.edu.retencion.chat.domain.Conversation;
import co.edu.retencion.chat.domain.ConversationRepository;
import co.edu.retencion.chat.domain.MessageReport;
import co.edu.retencion.chat.domain.MessageReportRepository;
import co.edu.retencion.chat.domain.Participant;
import co.edu.retencion.chat.domain.ParticipantRepository;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.AuthenticatedUser;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.common.web.CursorPage;
import co.edu.retencion.realtime.RealtimePublisher;
import co.edu.retencion.settings.SettingsProvider;
import co.edu.retencion.students.StudentInfo;
import co.edu.retencion.students.StudentRegistry;
import co.edu.retencion.users.UserDirectory;

/**
 * Casos de uso del chat. Reciben el usuario explícitamente porque se llaman tanto desde REST como
 * desde STOMP (donde no hay contexto de seguridad de hilo). El texto nunca se registra en logs ni
 * viaja en eventos: la entrega en tiempo real lee el mensaje después de confirmar la transacción.
 */
@Service
public class ChatService {

    private final ConversationRepository conversations;
    private final ParticipantRepository participants;
    private final ChatMessageRepository messages;
    private final ChatAttachmentRepository attachments;
    private final MessageReportRepository reports;
    private final ChatAccess access;
    private final StudentRegistry students;
    private final UserDirectory users;
    private final RealtimePublisher realtime;
    private final SettingsProvider settings;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ChatService(ConversationRepository conversations, ParticipantRepository participants,
                       ChatMessageRepository messages, ChatAttachmentRepository attachments,
                       MessageReportRepository reports, ChatAccess access, StudentRegistry students, UserDirectory users,
                       RealtimePublisher realtime, SettingsProvider settings, ApplicationEventPublisher events,
                       Clock clock) {
        this.conversations = conversations;
        this.participants = participants;
        this.messages = messages;
        this.attachments = attachments;
        this.reports = reports;
        this.access = access;
        this.students = students;
        this.users = users;
        this.realtime = realtime;
        this.settings = settings;
        this.events = events;
        this.clock = clock;
    }

    /** "Abrir chat": devuelve la conversación del par docente-estudiante o la crea. */
    @Transactional
    public ConversationView open(AuthenticatedUser actor, UUID studentId, UUID groupId) {
        if (!actor.hasRole(Role.DOCENTE)) {
            throw new AccessDeniedException("Solo un docente inicia conversaciones");
        }
        Set<UUID> shared = access.sharedGroups(actor.userId(), studentId);
        if (shared.isEmpty() || (groupId != null && !shared.contains(groupId))) {
            throw new AccessDeniedException("El estudiante no está inscrito en tus grupos del periodo activo");
        }
        StudentInfo student = students.findById(studentId).orElseThrow(() -> DomainException.notFound("Estudiante no encontrado"));
        Instant now = clock.instant();
        Conversation conversation = conversations.findByPairKey(Conversation.pairKey(actor.userId(), studentId))
                .orElseGet(() -> {
                    Conversation c = conversations.save(Conversation.teacherStudent(actor.userId(), studentId,
                            groupId != null ? groupId : shared.iterator().next(), now));
                    participants.save(Participant.of(c.getId(), actor.userId(), Participant.Role.DOCENTE, now));
                    return c;
                });
        ensureStudentParticipant(conversation, student, now);
        return view(conversation, actor.userId());
    }

    @Transactional
    public List<ConversationView> mine(AuthenticatedUser actor) {
        if (actor.hasRole(Role.ESTUDIANTE)) {
            // Conversaciones creadas antes de que el estudiante tuviera usuario: se suma al entrar.
            students.findStudentIdByUser(actor.userId()).ifPresent(studentId -> students.findById(studentId)
                    .ifPresent(s -> conversations.findByStudentId(studentId)
                            .forEach(c -> ensureStudentParticipant(c, s, clock.instant()))));
        }
        return conversations.findForUser(actor.userId()).stream().map(c -> view(c, actor.userId())).toList();
    }

    @Transactional(readOnly = true)
    public CursorPage<MessageView> history(AuthenticatedUser actor, UUID conversationId, UUID before, Integer size) {
        requireParticipant(actor.userId(), conversationId);
        return page(conversationId, before, size);
    }

    /** Lectura de ADMIN (con motivo, la audita el controlador). */
    @Transactional(readOnly = true)
    public CursorPage<MessageView> adminHistory(UUID conversationId, UUID before, Integer size) {
        conversations.findById(conversationId).orElseThrow(() -> DomainException.notFound("Conversación no encontrada"));
        return page(conversationId, before, size);
    }

    private CursorPage<MessageView> page(UUID conversationId, UUID before, Integer size) {
        int limit = CursorPage.clampSize(size);
        List<MessageView> rows = messages.page(conversationId, before == null, before, Limit.of(limit + 1)).stream()
                .map(this::view).toList();
        return CursorPage.of(rows, limit, MessageView::id);
    }

    @Transactional
    public MessageView send(AuthenticatedUser actor, UUID conversationId, ChatMessage.Type type, String text,
                            UUID attachmentId) {
        Conversation conversation = requireParticipant(actor.userId(), conversationId);
        requireWritable(conversation);
        Instant now = clock.instant();
        ChatMessage message = ChatMessage.send(conversationId, actor.userId(), type, text, attachmentId,
                settings.getInt("chat.max-message-length", 4000), now);
        AttachmentView attachmentView = null;
        if (attachmentId != null) {
            ChatAttachment attachment = attachments.findById(attachmentId)
                    .orElseThrow(() -> DomainException.badRequest("Adjunto inexistente"));
            if (type == ChatMessage.Type.TEXTO || (type == ChatMessage.Type.AUDIO) != attachment.isAudio()) {
                throw DomainException.badRequest("El tipo de mensaje no corresponde con el adjunto");
            }
            attachment.attachTo(message.getId(), actor.userId(), conversationId);
            attachmentView = AttachmentView.of(attachments.save(attachment));
        }
        messages.save(message);
        conversation.messageAt(now);
        conversations.save(conversation);
        List<UUID> recipients = participants.findByConversationId(conversationId).stream().map(Participant::getUserId)
                .filter(u -> !u.equals(actor.userId())).toList();
        events.publishEvent(new ChatMessageSent(conversationId, message.getId(), actor.userId(), recipients,
                conversation.getStudentId()));
        return MessageView.of(message, attachmentView);
    }

    /** Entrega en tiempo real a todos los participantes (también al emisor, por si tiene otros dispositivos). */
    @TransactionalEventListener
    void deliver(ChatMessageSent event) {
        messages.findById(event.messageId()).ifPresent(m -> {
            MessageView view = view(m);
            participants.findByConversationId(event.conversationId())
                    .forEach(p -> realtime.toUser(p.getUserId(), "chat", view));
        });
    }

    @Transactional
    public void markRead(AuthenticatedUser actor, UUID conversationId) {
        requireParticipant(actor.userId(), conversationId);
        Participant me = participants.findByConversationIdAndUserId(conversationId, actor.userId()).orElseThrow();
        Instant now = clock.instant();
        me.read(now);
        participants.save(me);
        ChatSignal receipt = new ChatSignal("READ", conversationId, actor.userId(), now);
        participants.findByConversationId(conversationId).stream().filter(p -> !p.getUserId().equals(actor.userId()))
                .forEach(p -> realtime.toUser(p.getUserId(), "chat-receipts", receipt));
    }

    /** Indicador "escribiendo" (efímero, no se guarda). */
    public void typing(AuthenticatedUser actor, UUID conversationId) {
        if (access.isParticipant(actor.userId(), conversationId)) {
            realtime.toTopic("chats." + conversationId, new ChatSignal("TYPING", conversationId, actor.userId(),
                    clock.instant()));
        }
    }

    @Transactional
    public MessageView delete(AuthenticatedUser actor, UUID conversationId, UUID messageId) {
        requireParticipant(actor.userId(), conversationId);
        ChatMessage message = messages.findById(messageId).filter(m -> m.getConversationId().equals(conversationId))
                .orElseThrow(() -> DomainException.notFound("Mensaje no encontrado"));
        message.deleteBy(actor.userId(), clock.instant());
        MessageView view = view(messages.save(message));
        participants.findByConversationId(conversationId).forEach(p -> realtime.toUser(p.getUserId(), "chat", view));
        return view;
    }

    @Transactional
    public ReportView report(AuthenticatedUser actor, UUID conversationId, UUID messageId, String reason) {
        requireParticipant(actor.userId(), conversationId);
        ChatMessage message = messages.findById(messageId).filter(m -> m.getConversationId().equals(conversationId))
                .orElseThrow(() -> DomainException.notFound("Mensaje no encontrado"));
        MessageReport report = reports.save(MessageReport.of(message.getId(), actor.userId(), reason, clock.instant()));
        return new ReportView(report.getId(), report.getMessageId(), report.getReporterId(), report.getReason(),
                report.getStatus(), report.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<ReportView> openReports() {
        return reports.findByStatusOrderByCreatedAtDesc("ABIERTO").stream().map(r -> new ReportView(r.getId(),
                r.getMessageId(), r.getReporterId(), r.getReason(), r.getStatus(), r.getCreatedAt())).toList();
    }

    boolean participates(UUID userId, UUID conversationId) {
        return access.isParticipant(userId, conversationId);
    }

    Conversation requireParticipant(UUID userId, UUID conversationId) {
        Conversation c = conversations.findById(conversationId)
                .orElseThrow(() -> DomainException.notFound("Conversación no encontrada"));
        if (!access.isParticipant(userId, conversationId)) {
            throw new AccessDeniedException("No participas en esta conversación");
        }
        return c;
    }

    void requireWritable(Conversation c) {
        if (access.sharedGroups(c.getOwnerUserId(), c.getStudentId()).isEmpty()) {
            throw new DomainException(ChatProblems.READ_ONLY, "La conversación es de solo lectura");
        }
    }

    private void ensureStudentParticipant(Conversation c, StudentInfo student, Instant now) {
        if (student.userId() != null && !participants.existsByConversationIdAndUserId(c.getId(), student.userId())) {
            participants.save(Participant.of(c.getId(), student.userId(), Participant.Role.ESTUDIANTE, now));
        }
    }

    private ConversationView view(Conversation c, UUID viewer) {
        Optional<Participant> me = participants.findByConversationIdAndUserId(c.getId(), viewer);
        Instant since = me.map(Participant::getLastReadAt).orElse(null);
        long unread = messages.unread(c.getId(), viewer, since == null, since == null ? Instant.EPOCH : since);
        boolean readOnly = access.sharedGroups(c.getOwnerUserId(), c.getStudentId()).isEmpty();
        String anon = students.findById(c.getStudentId()).map(StudentInfo::anonCode).orElse(null);
        return new ConversationView(c.getId(), c.getStudentId(), anon, c.getOwnerUserId(), c.getContextGroupId(), unread,
                readOnly, users.appointmentLink(c.getOwnerUserId()).orElse(null), c.getCreatedAt(), c.getLastMessageAt());
    }

    private MessageView view(ChatMessage m) {
        AttachmentView attachment = m.getAttachmentId() == null ? null
                : attachments.findById(m.getAttachmentId()).map(AttachmentView::of).orElse(null);
        return MessageView.of(m, attachment);
    }
}
