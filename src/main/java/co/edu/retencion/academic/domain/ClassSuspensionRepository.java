package co.edu.retencion.academic.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Repositorio de suspensiones de clase. */
public interface ClassSuspensionRepository extends JpaRepository<ClassSuspension, UUID> {

    /** Suspensiones que se cruzan con el rango [from, to]. */
    @Query("select s from ClassSuspension s where s.startDate <= :to and s.endDate >= :from order by s.startDate")
    List<ClassSuspension> overlapping(LocalDate from, LocalDate to);
}
