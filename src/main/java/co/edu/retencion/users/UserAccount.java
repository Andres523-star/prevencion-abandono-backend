package co.edu.retencion.users;

import java.util.Set;
import java.util.UUID;

import co.edu.retencion.common.security.Role;

/**
 * Vista pública de un usuario, sin datos personales.
 *
 * @param id         id interno
 * @param authType   SUPABASE o LOCAL
 * @param status     ACTIVE o DISABLED
 * @param roles      roles asignados en la plataforma
 * @param programIds programas en su alcance
 */
public record UserAccount(UUID id, AuthType authType, UserStatus status, Set<Role> roles, Set<UUID> programIds) {

    public UserAccount {
        roles = Set.copyOf(roles);
        programIds = Set.copyOf(programIds);
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }
}
