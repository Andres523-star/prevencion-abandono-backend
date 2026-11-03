package co.edu.retencion.academic.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Repositorio de periodos. */
public interface TermRepository extends JpaRepository<Term, UUID> {

    Optional<Term> findByCode(String code);

    List<Term> findAllByOrderByStartDateDesc();
}
