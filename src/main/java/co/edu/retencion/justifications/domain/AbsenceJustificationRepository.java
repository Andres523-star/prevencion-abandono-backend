package co.edu.retencion.justifications.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Repositorio de justificaciones. */
public interface AbsenceJustificationRepository extends JpaRepository<AbsenceJustification, UUID> {

    List<AbsenceJustification> findByAssignedTeacherIdAndStatusOrderBySubmittedAtAsc(UUID teacherId,
                                                                                   AbsenceJustification.Status status);

    List<AbsenceJustification> findByStudentIdOrderBySubmittedAtDesc(UUID studentId);

    List<AbsenceJustification> findByGroupIdAndStatus(UUID groupId, AbsenceJustification.Status status);
}
