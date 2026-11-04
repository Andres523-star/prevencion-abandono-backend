package co.edu.retencion.mailing.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailOutboxRepository extends JpaRepository<EmailOutbox, UUID> {

    Optional<EmailOutbox> findByDedupeKey(String dedupeKey);

    List<EmailOutbox> findByProviderMessageId(String providerMessageId);

    List<EmailOutbox> findByStudentIdOrderByCreatedAtDesc(UUID studentId);

    List<EmailOutbox> findByStatusOrderByCreatedAtDesc(EmailOutbox.Status status, Pageable page);

    List<EmailOutbox> findAllByOrderByCreatedAtDesc(Pageable page);
}
