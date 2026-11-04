package co.edu.retencion.wellbeing.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WellbeingAssessmentRepository extends JpaRepository<WellbeingAssessment, UUID> {

    List<WellbeingAssessment> findByStudentIdOrderByAssessedOnDesc(UUID studentId);

    long countByStudentIdAndType(UUID studentId, WellbeingAssessment.Type type);
}
