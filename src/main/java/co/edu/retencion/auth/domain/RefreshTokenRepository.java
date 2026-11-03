package co.edu.retencion.auth.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repositorio de refresh tokens. */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** Bloquea la fila: dos renovaciones simultáneas del mismo token no pueden ganar ambas. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :hash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("hash") String hash);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RefreshToken t set t.revokedAt = :now, t.revokedReason = :reason
            where t.familyId = :familyId and t.revokedAt is null
            """)
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now,
                     @Param("reason") RevocationReason reason);

    List<RefreshToken> findByFamilyIdOrderByIssuedAtAsc(UUID familyId);

    /** Limpieza: los tokens de sesiones vencidas hace tiempo ya no aportan nada. */
    @Modifying
    @Query("delete from RefreshToken t where t.familyExpiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
