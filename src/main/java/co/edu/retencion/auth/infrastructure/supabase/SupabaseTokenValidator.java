package co.edu.retencion.auth.infrastructure.supabase;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import co.edu.retencion.auth.domain.AuthProblems;
import co.edu.retencion.auth.infrastructure.AuthProperties;
import co.edu.retencion.common.error.DomainException;

/**
 * Valida localmente el access token que Supabase Auth entregó al frontend:
 * <ol>
 *   <li>Firma: claves públicas del proyecto (JWKS, ES256/RS256) o, en proyectos con el secreto
 *       heredado, HS256 con {@code SUPABASE_JWT_SECRET}.</li>
 *   <li>Vigencia ({@code exp}, {@code nbf}) con 30 s de tolerancia.</li>
 *   <li>Emisor {@code <url>/auth/v1} y audiencia {@code authenticated}.</li>
 *   <li>{@code role = authenticated} y que no sea un usuario anónimo.</li>
 * </ol>
 */
@Component
public class SupabaseTokenValidator {

    private final AuthProperties properties;
    private final Clock clock;
    private volatile NimbusJwtDecoder decoder;

    public SupabaseTokenValidator(AuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public Jwt validate(String accessToken) {
        try {
            return decoder().decode(accessToken);
        } catch (JwtException ex) {
            throw new DomainException(AuthProblems.INVALID_PROVIDER_TOKEN, "El token de Supabase no es válido o expiró");
        }
    }

    private NimbusJwtDecoder decoder() {
        NimbusJwtDecoder current = decoder;
        if (current == null) {
            synchronized (this) {
                if (decoder == null) {
                    decoder = buildDecoder();
                }
                current = decoder;
            }
        }
        return current;
    }

    private NimbusJwtDecoder buildDecoder() {
        AuthProperties.Supabase supabase = properties.supabase();
        NimbusJwtDecoder nimbus;
        if (supabase.jwtSecret() != null && !supabase.jwtSecret().isBlank()) {
            nimbus = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(
                            supabase.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                    .macAlgorithm(MacAlgorithm.HS256)
                    .build();
        } else {
            nimbus = NimbusJwtDecoder.withJwkSetUri(supabase.jwksUri())
                    .jwsAlgorithms(algs -> {
                        algs.add(SignatureAlgorithm.ES256);
                        algs.add(SignatureAlgorithm.RS256);
                    })
                    .build();
        }
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ofSeconds(30));
        timestamps.setClock(clock);
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(List.of(
                timestamps,
                new JwtIssuerValidator(supabase.issuer()),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.contains(supabase.audience())),
                SupabaseTokenValidator::requireAuthenticatedUser)));
        return nimbus;
    }

    private static OAuth2TokenValidatorResult requireAuthenticatedUser(Jwt jwt) {
        boolean authenticated = "authenticated".equals(jwt.getClaimAsString("role"));
        boolean anonymous = Boolean.TRUE.equals(jwt.getClaimAsBoolean("is_anonymous"));
        if (!authenticated || anonymous || jwt.getSubject() == null) {
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Se requiere un usuario autenticado (no anónimo)", null));
        }
        return OAuth2TokenValidatorResult.success();
    }
}
