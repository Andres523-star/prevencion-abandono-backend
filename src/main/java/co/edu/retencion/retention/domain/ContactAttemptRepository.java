package co.edu.retencion.retention.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Repositorio del módulo de retención. */
public interface ContactAttemptRepository extends JpaRepository<ContactAttempt, UUID> {

    List<ContactAttempt> findByCaseIdOrderByAttemptedAtAsc(UUID caseId);

    boolean existsByCaseIdAndResult(UUID caseId, ContactAttempt.Result result);
}
