package co.edu.retencion.chat.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Adjuntos. */
public interface ChatAttachmentRepository extends JpaRepository<ChatAttachment, UUID> {

    Optional<ChatAttachment> findByMessageId(UUID messageId);
}
