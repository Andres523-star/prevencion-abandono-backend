package co.edu.retencion.chat.application;

import java.time.Instant;
import java.util.UUID;

import co.edu.retencion.chat.domain.ChatAttachment;
import co.edu.retencion.chat.domain.ChatMessage;

/** DTO del chat (nunca entidades). */
public final class ChatViews {

    private ChatViews() {
    }

    /**
     * @param readOnly        el estudiante ya no está inscrito en un grupo del docente
     * @param appointmentLink enlace de citas del docente (lo ve el estudiante)
     */
    public record ConversationView(UUID id, UUID studentId, String studentAnonCode, UUID teacherUserId,
                                   UUID contextGroupId, long unread, boolean readOnly, String appointmentLink,
                                   Instant createdAt, Instant lastMessageAt) {
    }

    public record AttachmentView(UUID id, String name, String mediaType, long sizeBytes, Integer durationSeconds,
                                 ChatAttachment.ScanStatus scanStatus) {

        static AttachmentView of(ChatAttachment a) {
            return new AttachmentView(a.getId(), a.getOriginalName(), a.getMediaType(), a.getSizeBytes(),
                    a.getDurationSeconds(), a.getScanStatus());
        }

        @Override
        public String toString() {
            return "AttachmentView[" + id + "]";
        }
    }

    /** Mensaje entregado por REST o por STOMP ({@code event = MESSAGE | DELETED}). */
    public record MessageView(String event, UUID id, UUID conversationId, UUID senderId, ChatMessage.Type type,
                              String text, AttachmentView attachment, boolean deleted, Instant createdAt) {

        static MessageView of(ChatMessage m, AttachmentView attachment) {
            boolean hidden = !m.visible();
            return new MessageView(hidden ? "DELETED" : "MESSAGE", m.getId(), m.getConversationId(), m.getSenderId(),
                    m.getType(), hidden ? null : m.getText(), hidden ? null : attachment, hidden, m.getCreatedAt());
        }

        @Override
        public String toString() {
            return "MessageView[" + id + "]";
        }
    }

    /** Confirmación de lectura o indicador de escritura. */
    public record ChatSignal(String event, UUID conversationId, UUID userId, Instant at) {
    }

    public record ReportView(UUID id, UUID messageId, UUID reporterId, String reason, String status, Instant createdAt) {

        @Override
        public String toString() {
            return "ReportView[" + id + "]";
        }
    }
}
