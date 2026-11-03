package co.edu.retencion.users.application;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.users.AuthType;
import co.edu.retencion.users.UserStatus;

/**
 * DTOs de salida del módulo de usuarios (records). Las entidades JPA nunca salen por la API.
 */
public final class UserViews {

    private UserViews() {
    }

    /** Resumen para listados: sin datos personales. */
    public record UserSummaryView(UUID id, AuthType authType, UserStatus status, Set<Role> roles,
                                  Set<UUID> programIds, Instant createdAt, Instant lastLoginAt) {
    }

    /** Detalle con identidad: solo para roles autorizados y siempre auditado. */
    public record UserDetailView(UUID id, String email, String displayName, AuthType authType, UserStatus status,
                                 Set<Role> roles, Set<UUID> programIds, Instant createdAt, Instant lastLoginAt) {
    }

    /** Perfil propio del usuario autenticado. */
    public record MeView(UUID id, String email, String displayName, Set<Role> roles, Set<UUID> programIds,
                         boolean mfa, AuthType authType) {
    }
}
