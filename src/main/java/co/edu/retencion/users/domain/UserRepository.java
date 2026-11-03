package co.edu.retencion.users.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import co.edu.retencion.common.security.Role;

/** Repositorio de usuarios. Interno: los demás módulos usan {@code UserDirectory}. */
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByExternalIssuerAndExternalSubject(String issuer, String subject);

    Optional<User> findByEmailBlindIndex(String emailBlindIndex);

    /** Incluye usuarios borrados lógicamente (la restricción de la entidad los oculta). */
    @Query(value = "SELECT count(*) > 0 FROM users WHERE email_blind_index = :index", nativeQuery = true)
    boolean existsByEmailIndexIncludingDeleted(String index);

    /** Paginación keyset: UUID v7 es ordenable en el tiempo, así que ordenar por id es estable. */
    List<User> findByIdGreaterThanOrderByIdAsc(UUID after, Limit limit);

    List<User> findAllByOrderByIdAsc(Limit limit);

    @Query("""
            select u from User u join u.roleGrants g
            where g.role = :role and (:fromStart = true or u.id > :after)
            order by u.id
            """)
    List<User> findByRole(Role role, boolean fromStart, UUID after, Limit limit);
}
