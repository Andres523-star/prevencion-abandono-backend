package co.edu.retencion.auth.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.audit.AuditOutcome;
import co.edu.retencion.audit.AuditTrail;
import co.edu.retencion.auth.domain.AuthMethod;
import co.edu.retencion.auth.domain.AuthProblems;
import co.edu.retencion.auth.domain.MfaPolicy;
import co.edu.retencion.auth.domain.RefreshToken;
import co.edu.retencion.auth.domain.RefreshTokenRepository;
import co.edu.retencion.auth.domain.RevocationReason;
import co.edu.retencion.auth.infrastructure.AccessTokenIssuer;
import co.edu.retencion.auth.infrastructure.AuthProperties;
import co.edu.retencion.auth.infrastructure.SecureTokens;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.SessionRevoked;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.common.id.UuidV7;
import co.edu.retencion.users.UserAccount;
import co.edu.retencion.users.UserDirectory;

/**
 * Sesiones propias de la plataforma: apertura, renovación con rotación y detección de
 * reutilización, y cierre.
 */
@Service
public class SessionService {

    private final RefreshTokenRepository refreshTokens;
    private final AccessTokenIssuer accessTokens;
    private final UserDirectory users;
    private final AuditTrail audit;
    private final AuthProperties properties;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public SessionService(RefreshTokenRepository refreshTokens, AccessTokenIssuer accessTokens, UserDirectory users,
                          AuditTrail audit, AuthProperties properties, Clock clock, ApplicationEventPublisher events) {
        this.events = events;
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
        this.users = users;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /** Abre una sesión nueva tras autenticar. Aplica la política de MFA por rol. */
    @Transactional
    public SessionTokens open(UserAccount user, AuthMethod method, boolean mfa) {
        if (!user.isActive()) {
            throw new DomainException(AuthProblems.ACCOUNT_DISABLED, "La cuenta está desactivada");
        }
        MfaPolicy.enforce(user.roles(), mfa);
        Instant now = clock.instant();
        UUID familyId = UuidV7.newId();
        return issue(user, familyId, null, method, mfa, now, now.plus(properties.sessionMaxLifetime()));
    }

    /**
     * Renueva la sesión. El token presentado queda consumido y se emite uno nuevo de la misma
     * familia. Si el token ya estaba consumido se asume robo: se revoca la familia completa.
     *
     * <p>{@code noRollbackFor}: la revocación por reutilización debe persistir aunque se lance el error.
     */
    @Transactional(noRollbackFor = DomainException.class)
    public SessionTokens refresh(String rawRefreshToken) {
        Instant now = clock.instant();
        RefreshToken token = refreshTokens.findByTokenHashForUpdate(hash(rawRefreshToken))
                .orElseThrow(SessionService::invalidRefresh);

        if (token.isConsumed()) {
            refreshTokens.revokeFamily(token.getFamilyId(), now, RevocationReason.REUSE_DETECTED);
            events.publishEvent(new SessionRevoked(token.getUserId(), token.getFamilyId()));
            audit.recordAs(token.getUserId(), "AUTH_REFRESH_REUSE_DETECTED", "Session",
                    token.getFamilyId().toString(), AuditOutcome.FAILURE, Map.of());
            throw new DomainException(AuthProblems.REFRESH_TOKEN_REUSED,
                    "Por seguridad se cerraron todas las sesiones de este inicio de sesión. Ingresa de nuevo.");
        }
        if (token.isExpired(now)) {
            throw invalidRefresh();
        }

        Optional<UserAccount> user = users.findById(token.getUserId()).filter(UserAccount::isActive);
        if (user.isEmpty()) {
            refreshTokens.revokeFamily(token.getFamilyId(), now, RevocationReason.ACCOUNT_DISABLED);
            events.publishEvent(new SessionRevoked(token.getUserId(), null));
            throw new DomainException(AuthProblems.ACCOUNT_DISABLED, "La cuenta está desactivada");
        }
        try {
            // Si le asignaron un rol privilegiado después del login sin MFA, debe reautenticarse.
            MfaPolicy.enforce(user.get().roles(), token.isMfa());
        } catch (DomainException mfaRequired) {
            refreshTokens.revokeFamily(token.getFamilyId(), now, RevocationReason.MFA_REQUIRED);
            events.publishEvent(new SessionRevoked(token.getUserId(), token.getFamilyId()));
            throw mfaRequired;
        }

        token.markUsed(now);
        return issue(user.get(), token.getFamilyId(), token.getId(), token.getAuthMethod(), token.isMfa(), now,
                token.getFamilyExpiresAt());
    }

    /** Cierra la sesión: revoca todos los refresh tokens de la familia. */
    @Transactional
    public void logout(UUID sessionId) {
        refreshTokens.revokeFamily(sessionId, clock.instant(), RevocationReason.LOGOUT);
        CurrentUser.get().ifPresent(u -> events.publishEvent(new SessionRevoked(u.userId(), sessionId)));
    }

    /** Limpieza diaria de tokens de sesiones vencidas hace más de 7 días. */
    @Scheduled(cron = "0 30 3 * * *", zone = "America/Bogota")
    @Transactional
    public void purgeExpired() {
        refreshTokens.deleteExpiredBefore(clock.instant().minus(Duration.ofDays(7)));
    }

    private SessionTokens issue(UserAccount user, UUID familyId, UUID parentId, AuthMethod method, boolean mfa,
                                Instant now, Instant familyExpiresAt) {
        String raw = SecureTokens.randomToken();
        RefreshToken refresh = RefreshToken.issue(familyId, parentId, user.id(), hash(raw), method, mfa, now,
                now.plus(properties.refreshTokenTtl()), familyExpiresAt);
        refreshTokens.save(refresh);
        AccessTokenIssuer.IssuedToken access = accessTokens.issue(user, familyId, mfa, method);
        return new SessionTokens(access.token(), "Bearer",
                Duration.between(now, access.expiresAt()).toSeconds(), raw,
                Duration.between(now, refresh.getExpiresAt()).toSeconds(), user.id());
    }

    static String hash(String rawToken) {
        return SecureTokens.sha256Hex(rawToken);
    }

    private static DomainException invalidRefresh() {
        return new DomainException(AuthProblems.INVALID_REFRESH_TOKEN, "La sesión expiró o no es válida");
    }
}
