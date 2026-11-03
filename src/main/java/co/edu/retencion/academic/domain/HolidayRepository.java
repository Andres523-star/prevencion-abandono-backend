package co.edu.retencion.academic.domain;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** Repositorio de festivos. */
public interface HolidayRepository extends JpaRepository<Holiday, LocalDate> {

    List<Holiday> findByDateBetweenOrderByDateAsc(LocalDate from, LocalDate to);
}
