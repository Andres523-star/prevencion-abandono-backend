package co.edu.retencion.auth.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import co.edu.retencion.common.id.UuidV7;

/**
 * Refresh token rotativo. Solo se guarda el hash SHA-256 del token, nunca el valor.
 *
 * <p>Todos los tokens emitidos desde un mismo login forman una <em>familia</em> (= id de sesión).
 * Cada uso lo marca como consumido y emite un hijo. Si se presenta un token ya consumido,
 * alguien lo robó: se revoca la familia entera.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    private UUID id;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_method", nullable = false)
    private AuthMethod authMethod;

    @Column(nullable = false)
    private boolean mfa;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "family_expires_at", nullable = false)
    private Instant familyExpiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoked_reason")
    private RevocationReason revokedReason;

    /**
     * Emite un token de la familia. Su vencimiento nunca supera el de la familia (vida máxima
     * absoluta de la sesión, aunque se renueve continuamente).
     */
    public static RefreshToken issue(UUID familyId, UUID parentId, UUID userId, String tokenHash, AuthMethod method,
                                     boolean mfa, Instant now, Instant expiresAt, Instant familyExpiresAt) {
        RefreshToken token = new RefreshToken();
        token.id = UuidV7.newId();
        token.familyId = familyId;
        token.parentId = parentId;
        token.userId = userId;
        token.tokenHash = tokenHash;
        token.authMethod = method;
        token.mfa = mfa;
        token.issuedAt = now;
        token.expiresAt = expiresAt.isAfter(familyExpiresAt) ? familyExpiresAt : expiresAt;
        token.familyExpiresAt = familyExpiresAt;
        return token;
    }

    /** Ya fue usado o revocado: presentarlo de nuevo es reutilización. */
    public boolean isConsumed() {
        return usedAt != null || revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public void markUsed(Instant now) {
        this.usedAt = now;
    }
}
