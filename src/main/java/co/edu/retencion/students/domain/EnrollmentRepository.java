package co.edu.retencion.students.domain;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Repositorio de inscripciones. */
public interface EnrollmentRepository extends JpaRepository<Enrollment, UUID> {

    Optional<Enrollment> findByStudentIdAndGroupId(UUID studentId, UUID groupId);

    List<Enrollment> findByStudentIdOrderByEnrolledAtDesc(UUID studentId);

    List<Enrollment> findByStudentIdAndTermIdOrderByEnrolledAtDesc(UUID studentId, UUID termId);

    List<Enrollment> findByGroupIdAndStatus(UUID groupId, Enrollment.Status status);

    @Query("select distinct e.studentId from Enrollment e where e.termId = :termId")
    Set<UUID> findStudentIdsByTermId(UUID termId);

    long countByGroupIdAndStatus(UUID groupId, Enrollment.Status status);

    /** Grupos activos del estudiante (base del ABAC del docente sobre el estudiante). */
    @Query("select e.groupId from Enrollment e where e.studentId = :studentId and e.status = co.edu.retencion.students.domain.Enrollment.Status.ACTIVA")
    Set<UUID> findActiveGroupIds(UUID studentId);
}
