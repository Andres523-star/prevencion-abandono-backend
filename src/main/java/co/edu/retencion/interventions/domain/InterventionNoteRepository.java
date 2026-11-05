package co.edu.retencion.interventions.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Notas de seguimiento. */
public interface InterventionNoteRepository extends JpaRepository<InterventionNote, UUID> {

    List<InterventionNote> findByInterventionIdOrderByCreatedAtAsc(UUID interventionId);
}
