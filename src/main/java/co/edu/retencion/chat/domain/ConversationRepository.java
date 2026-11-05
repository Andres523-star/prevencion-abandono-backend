package co.edu.retencion.chat.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Conversaciones. */
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    Optional<Conversation> findByPairKey(String pairKey);

    List<Conversation> findByStudentId(UUID studentId);

    @Query("""
            select c from Conversation c where c.id in
              (select p.conversationId from Participant p where p.userId = :userId)
            order by coalesce(c.lastMessageAt, c.createdAt) desc
            """)
    List<Conversation> findForUser(UUID userId);
}
