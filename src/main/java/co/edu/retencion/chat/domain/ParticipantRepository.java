package co.edu.retencion.chat.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Participantes. */
public interface ParticipantRepository extends JpaRepository<Participant, Participant.Key> {

    List<Participant> findByConversationId(UUID conversationId);

    Optional<Participant> findByConversationIdAndUserId(UUID conversationId, UUID userId);

    boolean existsByConversationIdAndUserId(UUID conversationId, UUID userId);
}
