package co.edu.retencion.retention.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Repositorio del módulo de retención. */
public interface CaseTaskRepository extends JpaRepository<CaseTask, UUID> {

    List<CaseTask> findByCaseIdOrderByDueDateAsc(UUID caseId);

    long countByCaseId(UUID caseId);
}
