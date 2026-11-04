package co.edu.retencion.wellbeing.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WellbeingReferralRepository extends JpaRepository<WellbeingReferral, UUID> {

    List<WellbeingReferral> findByStudentIdOrderByCreatedAtDesc(UUID studentId);

    long countByStudentIdAndStatus(UUID studentId, WellbeingReferral.Status status);
}
