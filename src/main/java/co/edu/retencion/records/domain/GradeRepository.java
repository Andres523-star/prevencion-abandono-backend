package co.edu.retencion.records.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** Repositorio del módulo records. */
public interface GradeRepository extends JpaRepository<Grade, UUID> {

    List<Grade> findByGroupIdOrderByAssessedOnAsc(UUID groupId);

    List<Grade> findByStudentIdAndTermIdOrderByAssessedOnAsc(UUID studentId, UUID termId);

    List<Grade> findByStudentIdOrderByAssessedOnAsc(UUID studentId);

    @Query("""
            select g from Grade g where g.enrollmentId = :enrollmentId and g.assessedOn = :assessedOn
              and coalesce(g.description, '') = coalesce(:description, '')
            """)
    Optional<Grade> findNatural(UUID enrollmentId, LocalDate assessedOn, String description);
}
