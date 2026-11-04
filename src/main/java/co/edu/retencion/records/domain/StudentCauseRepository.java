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
public interface StudentCauseRepository extends JpaRepository<StudentCause, UUID> {

    List<StudentCause> findByStudentIdOrderByRecordedAtDesc(UUID studentId);

    Optional<StudentCause> findByStudentIdAndCauseCodeAndResolvedAtIsNull(UUID studentId, String causeCode);
}
