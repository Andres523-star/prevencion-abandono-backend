package co.edu.retencion.chat.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Mensajes (paginación keyset por id: UUID v7 creciente en el tiempo). */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    @Query("""
            select m from ChatMessage m where m.conversationId = :conversationId
              and (:fromStart = true or m.id < :before)
            order by m.id desc
            """)
    List<ChatMessage> page(UUID conversationId, boolean fromStart, UUID before, Limit limit);

    @Query("""
            select count(m) from ChatMessage m where m.conversationId = :conversationId and m.senderId <> :userId
              and m.deletedAt is null and (:never = true or m.createdAt > :since)
            """)
    long unread(UUID conversationId, UUID userId, boolean never, Instant since);

    @Query("select m from ChatMessage m where m.createdAt < :before and m.purgedAt is null")
    List<ChatMessage> findExpired(Instant before, Limit limit);

    List<ChatMessage> findByConversationIdAndPurgedAtIsNull(UUID conversationId);
}
