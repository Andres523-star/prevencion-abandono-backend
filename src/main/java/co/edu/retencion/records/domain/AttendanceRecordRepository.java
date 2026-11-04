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
public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, UUID> {

    /** Filtrar por term_id permite a PostgreSQL leer solo la partición del periodo. */
    Optional<AttendanceRecord> findByTermIdAndSessionIdAndEnrollmentId(UUID termId, UUID sessionId, UUID enrollmentId);

    List<AttendanceRecord> findByTermIdAndSessionId(UUID termId, UUID sessionId);

    List<AttendanceRecord> findByTermIdAndEnrollmentId(UUID termId, UUID enrollmentId);

    List<AttendanceRecord> findByStudentIdOrderByRecordedAtDesc(UUID studentId);

    @Query("""
            select r from AttendanceRecord r
            where r.status = co.edu.retencion.records.AttendanceStatus.FALTA
              and r.justification = co.edu.retencion.records.JustificationState.NINGUNA
              and r.justificationDeadline < :today
            """)
    List<AttendanceRecord> findOverdue(LocalDate today);
}
