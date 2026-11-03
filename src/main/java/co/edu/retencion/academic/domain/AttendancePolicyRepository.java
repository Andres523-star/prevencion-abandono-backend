package co.edu.retencion.academic.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Repositorio de políticas de asistencia. */
public interface AttendancePolicyRepository extends JpaRepository<AttendancePolicy, UUID> {

    Optional<AttendancePolicy> findByScopeAndProgramId(AttendancePolicy.Scope scope, UUID programId);

    Optional<AttendancePolicy> findBySubjectId(UUID subjectId);
}
