package co.edu.retencion.retention.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Casos de retención. */
public interface RetentionCaseRepository extends JpaRepository<RetentionCase, UUID> {

    @Query("select c from RetentionCase c where c.studentId = :studentId and c.stage <> 'CERRADO'")
    Optional<RetentionCase> findOpenByStudent(UUID studentId);

    List<RetentionCase> findByStudentIdOrderByOpenedAtDesc(UUID studentId);

    long countByAssignedCounselorIdAndStageNot(UUID counselor, RetentionCase.Stage stage);

    @Query("""
            select c from RetentionCase c where c.stage = 'CERRADO' and c.outcome in :outcomes and c.closedAt <= :before
            """)
    List<RetentionCase> findClosedBefore(Collection<RetentionCase.Outcome> outcomes, Instant before);

    @Query("""
            select c from RetentionCase c
            where (:anyProgram = true or c.programId in :programs)
              and (:anyTerm = true or c.termId = :termId)
            """)
    List<RetentionCase> funnel(boolean anyProgram, Collection<UUID> programs, boolean anyTerm, UUID termId);
}
