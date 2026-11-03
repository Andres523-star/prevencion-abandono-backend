package co.edu.retencion.auth.infrastructure;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import co.edu.retencion.auth.domain.AuthMethod;
import co.edu.retencion.common.id.UuidV7;
import co.edu.retencion.common.security.jwt.PlatformClaims;
import co.edu.retencion.common.security.jwt.PlatformJwtProperties;
import co.edu.retencion.users.UserAccount;

/**
 * Emite el JWT de acceso propio de la plataforma (HS256, 15 min). No lleva datos personales:
 * solo el id interno, roles, programas en alcance y si la sesión tiene MFA.
 */
@Component
public class AccessTokenIssuer {

    /**
     * @param token     JWT firmado
     * @param expiresAt vencimiento
     */
    public record IssuedToken(String token, Instant expiresAt) {

        @Override
        public String toString() {
            return "IssuedToken[expiresAt=" + expiresAt + "]";
        }
    }

    private final JwtEncoder encoder;
    private final PlatformJwtProperties properties;
    private final Clock clock;

    public AccessTokenIssuer(JwtEncoder encoder, PlatformJwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedToken issue(UserAccount user, UUID sessionId, boolean mfa, AuthMethod method) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(user.id().toString())
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(expiresAt)
                .id(UuidV7.newId().toString())
                .claim(PlatformClaims.SESSION_ID, sessionId.toString())
                .claim(PlatformClaims.ROLES, user.roles().stream().map(Enum::name).sorted().toList())
                .claim(PlatformClaims.PROGRAMS, user.programIds().stream().map(UUID::toString).sorted().toList())
                .claim(PlatformClaims.MFA, mfa)
                .claim(PlatformClaims.AUTH_METHOD, method.name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }
}
