package co.edu.retencion.students.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Repositorio de identidades (cifradas). */
public interface StudentIdentityRepository extends JpaRepository<StudentIdentity, UUID> {

    Optional<StudentIdentity> findByDocumentBlindIndex(String documentBlindIndex);

    List<StudentIdentity> findByEmailBlindIndex(String emailBlindIndex);

    List<StudentIdentity> findByStudentIdIn(Collection<UUID> studentIds);
}
