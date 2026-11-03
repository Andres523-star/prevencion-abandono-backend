package co.edu.retencion.academic.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Repositorio de materias. */
public interface SubjectRepository extends JpaRepository<Subject, UUID> {

    Optional<Subject> findByProgramIdAndCodeIgnoreCase(UUID programId, String code);

    List<Subject> findByProgramIdOrderBySuggestedSemesterAscCodeAsc(UUID programId);

    List<Subject> findByProgramIdAndCodeIn(UUID programId, Collection<String> codes);
}
