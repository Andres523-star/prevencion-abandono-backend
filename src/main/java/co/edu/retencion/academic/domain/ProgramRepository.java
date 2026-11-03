package co.edu.retencion.academic.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Repositorio de programas. */
public interface ProgramRepository extends JpaRepository<Program, UUID> {

    Optional<Program> findByCodeIgnoreCase(String code);

    List<Program> findAllByOrderByNameAsc();

    @Query("select p.id from Program p where p.id in :ids")
    List<UUID> findExistingIds(Collection<UUID> ids);
}
