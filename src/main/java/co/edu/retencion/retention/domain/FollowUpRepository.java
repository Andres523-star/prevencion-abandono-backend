package co.edu.retencion.retention.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Repositorio del módulo de retención. */
public interface FollowUpRepository extends JpaRepository<FollowUp, UUID> {

    List<FollowUp> findByCaseIdOrderByDueOnAsc(UUID caseId);

    List<FollowUp> findByStatusAndDueOnLessThanEqualAndRemindedAtIsNull(FollowUp.Status status, java.time.LocalDate today);

    boolean existsByCaseIdAndKindAndDueOnAfter(UUID caseId, FollowUp.Kind kind, java.time.LocalDate after);
}
