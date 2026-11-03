package co.edu.retencion.common.security;

import java.util.Set;
import java.util.UUID;

/**
 * Usuario autenticado de la petición en curso, construido a partir del JWT de la plataforma.
 * No contiene datos personales (ni nombre ni correo): solo el id interno, roles y alcance.
 *
 * @param userId     id interno del usuario
 * @param roles      roles efectivos (los que exigen MFA solo aparecen si la sesión tiene MFA)
 * @param programIds programas en los que el usuario tiene alcance (coordinador, consejero)
 * @param mfa        si la sesión se abrió con autenticación multifactor
 * @param sessionId  identificador de la familia de refresh tokens (para logout)
 */
public record AuthenticatedUser(UUID userId, Set<Role> roles, Set<UUID> programIds, boolean mfa, UUID sessionId) {

    public AuthenticatedUser {
        roles = Set.copyOf(roles);
        programIds = Set.copyOf(programIds);
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }
}
