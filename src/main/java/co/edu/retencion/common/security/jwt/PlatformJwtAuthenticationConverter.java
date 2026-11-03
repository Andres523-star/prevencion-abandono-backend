package co.edu.retencion.common.security.jwt;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import co.edu.retencion.common.security.AuthenticatedUser;
import co.edu.retencion.common.security.PlatformAuthentication;
import co.edu.retencion.common.security.Role;

/**
 * Convierte el JWT de la plataforma en {@link PlatformAuthentication}.
 *
 * <p>Defensa en profundidad: aunque el emisor ya exige MFA, si un token sin MFA trae un rol
 * que lo requiere, ese rol se descarta y el usuario no obtiene sus permisos.
 */
@Component
public class PlatformJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        boolean mfa = Boolean.TRUE.equals(jwt.getClaimAsBoolean(PlatformClaims.MFA));
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (String value : Optional.ofNullable(jwt.getClaimAsStringList(PlatformClaims.ROLES)).orElse(List.of())) {
            parseRole(value).filter(role -> mfa || !role.requiresMfa()).ifPresent(roles::add);
        }
        Set<UUID> programs = Optional.ofNullable(jwt.getClaimAsStringList(PlatformClaims.PROGRAMS)).orElse(List.of())
                .stream().map(UUID::fromString).collect(Collectors.toUnmodifiableSet());
        AuthenticatedUser user = new AuthenticatedUser(
                UUID.fromString(jwt.getSubject()),
                roles,
                programs,
                mfa,
                UUID.fromString(jwt.getClaimAsString(PlatformClaims.SESSION_ID)));
        return new PlatformAuthentication(user, jwt);
    }

    private static Optional<Role> parseRole(String value) {
        try {
            return Optional.of(Role.valueOf(value));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
