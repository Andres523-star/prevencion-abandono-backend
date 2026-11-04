package co.edu.retencion.justifications.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Repositorio de evidencias. */
public interface JustificationEvidenceRepository extends JpaRepository<JustificationEvidence, UUID> {

    List<JustificationEvidence> findByJustificationIdOrderByUploadedAtAsc(UUID justificationId);

    List<JustificationEvidence> findByJustificationIdIn(Collection<UUID> justificationIds);

    List<JustificationEvidence> findByScanStatusIn(Collection<JustificationEvidence.ScanStatus> statuses);

    long countByJustificationId(UUID justificationId);
}
