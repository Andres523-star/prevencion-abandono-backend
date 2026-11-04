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
public interface AbsenceCounterRepository extends JpaRepository<AbsenceCounter, UUID> {

    /** Bloquea el contador: dos tomas de asistencia simultáneas no se pisan. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AbsenceCounter c where c.enrollmentId = :enrollmentId")
    Optional<AbsenceCounter> findForUpdate(UUID enrollmentId);

    List<AbsenceCounter> findByGroupId(UUID groupId);

    List<AbsenceCounter> findByStudentIdAndTermId(UUID studentId, UUID termId);

    List<AbsenceCounter> findByStudentId(UUID studentId);
}
