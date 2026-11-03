package co.edu.retencion.auth.domain;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repositorio de credenciales break-glass. */
public interface LocalCredentialRepository extends JpaRepository<LocalCredential, UUID> {

    Optional<LocalCredential> findByUsername(String username);

    /** Bloqueo de fila para que los intentos concurrentes cuenten correctamente. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from LocalCredential c where c.username = :username")
    Optional<LocalCredential> findByUsernameForUpdate(@Param("username") String username);
}
