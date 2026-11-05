package co.edu.retencion.risk.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** Estado del motor por estudiante. */
public interface RiskStudentStateRepository extends JpaRepository<RiskStudentState, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RiskStudentState s where s.studentId = :studentId")
    Optional<RiskStudentState> findForUpdate(UUID studentId);

    /** Último nivel por estudiante para las métricas de sesgo. */
    @Query("select s from RiskStudentState s where s.lastLevel is not null")
    List<RiskStudentState> findAssessed();

    @Query("""
            select s from RiskStudentState s
            where s.lastLevel in :levels
              and (:anyProgram = true or s.programId in :programs)
              and (:anyStudent = true or s.studentId in :students)
              and (:fromStart = true or s.studentId > :after)
            order by s.studentId
            """)
    List<RiskStudentState> page(java.util.Collection<co.edu.retencion.risk.RiskLevel> levels, boolean anyProgram,
                                java.util.Collection<UUID> programs, boolean anyStudent, java.util.Collection<UUID> students,
                                boolean fromStart, UUID after, org.springframework.data.domain.Limit limit);

    List<RiskStudentState> findByStudentIdIn(java.util.Collection<UUID> students);
}
