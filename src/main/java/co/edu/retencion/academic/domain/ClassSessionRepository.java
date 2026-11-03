package co.edu.retencion.academic.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Repositorio de sesiones de clase. */
public interface ClassSessionRepository extends JpaRepository<ClassSession, UUID> {

    List<ClassSession> findByGroupIdAndDateBetweenOrderByDateAscStartTimeAsc(UUID groupId, LocalDate from,
                                                                              LocalDate to);

    long countByGroupIdAndStatus(UUID groupId, ClassSession.Status status);
}
