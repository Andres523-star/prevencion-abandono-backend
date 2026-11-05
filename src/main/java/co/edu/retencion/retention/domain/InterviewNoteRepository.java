package co.edu.retencion.retention.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Repositorio del módulo de retención. */
public interface InterviewNoteRepository extends JpaRepository<InterviewNote, UUID> {

    List<InterviewNote> findByCaseIdOrderByCreatedAtAsc(UUID caseId);
}
