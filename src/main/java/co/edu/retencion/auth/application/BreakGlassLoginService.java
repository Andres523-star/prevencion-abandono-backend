package co.edu.retencion.auth.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.auth.domain.AuthMethod;
import co.edu.retencion.auth.domain.AuthProblems;
import co.edu.retencion.auth.domain.LocalCredential;
import co.edu.retencion.auth.domain.LocalCredentialRepository;
import co.edu.retencion.auth.domain.LockoutPolicy;
import co.edu.retencion.auth.domain.Totp;
import co.edu.retencion.auth.infrastructure.AuthProperties;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.settings.SettingsProvider;
import co.edu.retencion.users.UserAccount;
import co.edu.retencion.users.UserDirectory;

/**
 * Login local de emergencia (break-glass) para ADMIN cuando Supabase no está disponible:
 * contraseña argon2id + código TOTP, con bloqueo progresivo configurable en caliente.
 *
 * <p>Contra enumeración de usuarios: el error es siempre el mismo ("credenciales inválidas") y,
 * si el usuario no existe, igual se calcula un hash argon2 para igualar el tiempo de respuesta.
 */
@Service
public class BreakGlassLoginService {

    static final String MAX_ATTEMPTS_KEY = "auth.lockout.max-attempts";
    static final String BASE_LOCK_KEY = "auth.lockout.base-lock-seconds";
    static final String MAX_LOCK_KEY = "auth.lockout.max-lock-seconds";

    private final AuthProperties properties;
    private final LocalCredentialRepository credentials;
    private final PasswordEncoder passwordEncoder;
    private final SettingsProvider settings;
    private final UserDirectory users;
    private final SessionService sessions;
    private final Clock clock;
    private final String dummyHash;

    public BreakGlassLoginService(AuthProperties properties, LocalCredentialRepository credentials,
                                  PasswordEncoder passwordEncoder, SettingsProvider settings, UserDirectory users,
                                  SessionService sessions, Clock clock) {
        this.properties = properties;
        this.credentials = credentials;
        this.passwordEncoder = passwordEncoder;
        this.settings = settings;
        this.users = users;
        this.sessions = sessions;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("hash-de-relleno-para-igualar-tiempos");
    }

    /** {@code noRollbackFor}: el contador de fallos debe guardarse aunque se rechace el login. */
    @Transactional(noRollbackFor = DomainException.class)
    public SessionTokens login(String username, String password, String totpCode) {
        if (!properties.breakGlass().enabled()) {
            throw DomainException.notFound("Recurso no encontrado");
        }
        Instant now = clock.instant();
        Optional<LocalCredential> found = credentials.findByUsernameForUpdate(username.trim().toLowerCase(Locale.ROOT));
        if (found.isEmpty()) {
            passwordEncoder.matches(password, dummyHash);
            throw invalidCredentials();
        }
        LocalCredential credential = found.get();
        if (credential.isLocked(now)) {
            // No se revela que está bloqueada ni se extiende el bloqueo.
            throw invalidCredentials();
        }

        boolean passwordOk = passwordEncoder.matches(password, credential.getPasswordHash());
        OptionalLong step = passwordOk
                ? Totp.verify(Totp.decodeBase32(credential.getTotpSecret()), totpCode, now, credential.getLastTotpStep())
                : OptionalLong.empty();
        if (step.isEmpty()) {
            credential.registerFailure(now, lockoutPolicy());
            throw invalidCredentials();
        }
        credential.registerSuccess(step.getAsLong(), now);

        UserAccount user = users.findById(credential.getUserId())
                .filter(UserAccount::isActive)
                .filter(u -> u.roles().contains(Role.ADMIN))
                .orElseThrow(BreakGlassLoginService::invalidCredentials);
        // Contraseña + TOTP = dos factores: la sesión cuenta como MFA.
        SessionTokens session = sessions.open(user, AuthMethod.LOCAL, true);
        users.recordLogin(user.id());
        return session;
    }

    /** La política se lee de settings en cada intento: cambiarla no requiere redeploy. */
    LockoutPolicy lockoutPolicy() {
        return new LockoutPolicy(
                settings.getInt(MAX_ATTEMPTS_KEY, 3),
                settings.getSeconds(BASE_LOCK_KEY, Duration.ofMinutes(1)),
                settings.getSeconds(MAX_LOCK_KEY, Duration.ofHours(24)));
    }

    private static DomainException invalidCredentials() {
        return new DomainException(AuthProblems.INVALID_CREDENTIALS, "Usuario, contraseña o código incorrectos");
    }
}
