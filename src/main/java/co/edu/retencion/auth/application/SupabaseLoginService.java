package co.edu.retencion.auth.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import co.edu.retencion.auth.domain.AuthMethod;
import co.edu.retencion.auth.domain.AuthProblems;
import co.edu.retencion.auth.infrastructure.AuthProperties;
import co.edu.retencion.auth.infrastructure.SecureTokens;
import co.edu.retencion.auth.infrastructure.supabase.SupabaseAuthClient;
import co.edu.retencion.auth.infrastructure.supabase.SupabaseAuthClient.SupabaseUser;
import co.edu.retencion.auth.infrastructure.supabase.SupabaseIdentityAdapter;
import co.edu.retencion.auth.infrastructure.supabase.SupabaseIdentityAdapter.ExternalIdentity;
import co.edu.retencion.auth.infrastructure.supabase.SupabaseTokenValidator;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.state.OneTimeTicketStore;
import co.edu.retencion.users.UserAccount;
import co.edu.retencion.users.UserDirectory;

/**
 * Canje del token de Supabase Auth por una sesión de la plataforma.
 *
 * <p>El frontend inicia sesión con {@code supabase-js} (correo + contraseña, enlace mágico,
 * Google, Microsoft...) y envía aquí el {@code access_token}. El backend:
 * <ol>
 *   <li>valida firma, emisor, audiencia y vigencia del token;</li>
 *   <li>impide canjear dos veces el mismo token (anti-replay);</li>
 *   <li>consulta a Supabase el usuario y exige el correo confirmado;</li>
 *   <li>vincula o crea el usuario interno (los roles se asignan en la plataforma);</li>
 *   <li>exige MFA ({@code aal2}) a los roles privilegiados y emite la sesión propia.</li>
 * </ol>
 */
@Service
public class SupabaseLoginService {

    private final AuthProperties properties;
    private final SupabaseTokenValidator tokenValidator;
    private final SupabaseAuthClient authClient;
    private final SupabaseIdentityAdapter identityAdapter;
    private final OneTimeTicketStore exchangedTokens;
    private final UserDirectory users;
    private final SessionService sessions;
    private final Clock clock;

    public SupabaseLoginService(AuthProperties properties, SupabaseTokenValidator tokenValidator,
                                SupabaseAuthClient authClient, SupabaseIdentityAdapter identityAdapter,
                                OneTimeTicketStore exchangedTokens, UserDirectory users, SessionService sessions,
                                Clock clock) {
        this.properties = properties;
        this.tokenValidator = tokenValidator;
        this.authClient = authClient;
        this.identityAdapter = identityAdapter;
        this.exchangedTokens = exchangedTokens;
        this.users = users;
        this.sessions = sessions;
        this.clock = clock;
    }

    public SessionTokens exchange(String supabaseAccessToken) {
        AuthProperties.Supabase supabase = properties.supabase();
        if (!supabase.isConfigured()) {
            throw new DomainException(AuthProblems.AUTH_NOT_CONFIGURED,
                    "Configura SUPABASE_URL y SUPABASE_ANON_KEY para habilitar el inicio de sesión");
        }
        Jwt token = tokenValidator.validate(supabaseAccessToken);

        // Un token robado no sirve para abrir varias sesiones: cada uno se canjea una sola vez.
        Duration untilExpiry = Duration.between(clock.instant(), expiryOf(token)).plusMinutes(1);
        if (!exchangedTokens.claim(SecureTokens.sha256Hex(supabaseAccessToken), untilExpiry)) {
            throw new DomainException(AuthProblems.PROVIDER_TOKEN_ALREADY_USED, "Ese token ya se canjeó");
        }

        SupabaseUser supabaseUser;
        try {
            supabaseUser = authClient.fetchUser(supabaseAccessToken);
        } catch (CallNotPermittedException circuitOpen) {
            throw new DomainException(AuthProblems.AUTH_PROVIDER_UNAVAILABLE,
                    "Supabase no responde; intenta de nuevo en unos minutos");
        }
        ExternalIdentity external = identityAdapter.adapt(token, supabaseUser);

        if (supabase.requireVerifiedEmail() && !external.emailVerified()) {
            throw new DomainException(AuthProblems.EMAIL_NOT_VERIFIED,
                    "Confirma tu correo con el enlace que te enviamos y vuelve a ingresar");
        }
        if (!properties.isAllowedEmail(external.identity().email())) {
            throw new DomainException(AuthProblems.EMAIL_DOMAIN_NOT_ALLOWED, "El dominio de tu correo no está permitido");
        }

        UserAccount user = users.linkOrProvision(external.identity());
        // Releer: al crearse la cuenta, otro módulo pudo vincularla (p. ej. con su registro de estudiante) y darle un rol.
        user = users.findById(user.id()).orElse(user);
        boolean mfa = supabase.mfaAal().equals(external.aal());
        SessionTokens session = sessions.open(user, AuthMethod.SUPABASE, mfa);
        users.recordLogin(user.id());
        return session;
    }

    private Instant expiryOf(Jwt token) {
        return token.getExpiresAt() != null ? token.getExpiresAt() : clock.instant().plus(Duration.ofHours(1));
    }
}
