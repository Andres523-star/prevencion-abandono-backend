package co.edu.retencion.chat;

import java.util.List;
import java.util.UUID;

/**
 * Evento: se envió un mensaje. Solo ids: el texto nunca viaja en eventos (el registro de
 * publicaciones los guarda en la base y podrían salir a RabbitMQ).
 *
 * @param conversationId conversación
 * @param messageId      mensaje
 * @param senderId       emisor
 * @param recipientIds   demás participantes
 * @param studentId      estudiante de la conversación
 */
public record ChatMessageSent(UUID conversationId, UUID messageId, UUID senderId, List<UUID> recipientIds,
                              UUID studentId) {

    public ChatMessageSent {
        recipientIds = List.copyOf(recipientIds);
    }
}
