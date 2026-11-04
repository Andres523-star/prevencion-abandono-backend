package co.edu.retencion.risk.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Planes de apoyo. */
public interface SupportPlanRepository extends JpaRepository<SupportPlan, UUID> {

    List<SupportPlan> findByStudentIdOrderByCreatedAtDesc(UUID studentId);

    Optional<SupportPlan> findByEnrollmentIdAndStatus(UUID enrollmentId, SupportPlan.Status status);
}
