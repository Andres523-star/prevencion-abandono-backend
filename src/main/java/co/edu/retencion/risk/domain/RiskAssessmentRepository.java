package co.edu.retencion.risk.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Historial de análisis (append-only). */
public interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, UUID> {

    Optional<RiskAssessment> findFirstByStudentIdOrderByCreatedAtDescIdDesc(UUID studentId);

    long countByStudentId(UUID studentId);

    /** Keyset del más reciente al más antiguo: los UUID v7 son crecientes en el tiempo. */
    @Query("""
            select a from RiskAssessment a
            where a.studentId = :studentId and (:fromStart = true or a.id < :before)
            order by a.id desc
            """)
    List<RiskAssessment> history(UUID studentId, boolean fromStart, UUID before, Limit limit);
}
