package co.edu.retencion.users.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repositorio de usuarios. Interno: los demás módulos usan {@code UserDirectory}. */
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByExternalIssuerAndExternalSubject(String issuer, String subject);

    Optional<User> findByEmailBlindIndex(String emailBlindIndex);

    /** Paginación keyset: UUID v7 es ordenable en el tiempo, así que ordenar por id es estable. */
    List<User> findByIdGreaterThanOrderByIdAsc(UUID after, Limit limit);

    List<User> findAllByOrderByIdAsc(Limit limit);
}
