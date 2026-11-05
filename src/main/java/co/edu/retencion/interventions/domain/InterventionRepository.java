package co.edu.retencion.interventions.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Intervenciones. */
public interface InterventionRepository extends JpaRepository<Intervention, UUID> {

    List<Intervention> findByStudentIdOrderByCreatedAtDesc(UUID studentId);

    List<Intervention> findByResponsibleUserIdOrderByCreatedAtDesc(UUID responsibleUserId);

    List<Intervention> findByPlanId(UUID planId);
}
