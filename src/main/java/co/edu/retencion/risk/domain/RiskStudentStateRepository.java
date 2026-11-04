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
}
