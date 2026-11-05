package co.edu.retencion.analytics.domain;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Repositorio de la serie histórica. */
public interface DropoutHistoryRepository extends JpaRepository<DropoutHistory, UUID> {

    Optional<DropoutHistory> findByProgramIdAndPeriodMonthAndSemester(UUID programId, LocalDate month, Integer semester);

    Optional<DropoutHistory> findByProgramIdAndPeriodMonthAndSemesterIsNull(UUID programId, LocalDate month);
}
