package co.edu.retencion.users;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import co.edu.retencion.common.data.UpsertOutcome;
import co.edu.retencion.common.security.Role;

/**
 * Puerta de entrada pública al módulo de usuarios. La usan auth (vincular identidades y abrir
 * sesiones), academic (validar docentes), students (vincular usuario-estudiante) e imports.
 * Nunca devuelve datos personales en claro.
 */
public interface UserDirectory {

    Optional<UserAccount> findById(UUID userId);

    /**
     * Busca el usuario por su identidad externa (emisor + subject); si no existe lo vincula por
     * correo verificado o lo crea. Los roles NUNCA se toman del token.
     *
     * @throws co.edu.retencion.common.error.DomainException si el correo ya está vinculado a otra identidad
     */
    UserAccount linkOrProvision(FederatedIdentity identity);

    /** Crea una cuenta local (break-glass). Solo la usa el arranque de cuentas de emergencia. */
    UserAccount createLocalAccount(String label, Set<Role> roles);

    void recordLogin(UUID userId);

    /**
     * Usuarios activos con el rol; si {@code programId} no es nulo, solo los que tienen ese programa
     * en su alcance o ninguno (transversales). Ordenados por id. Sirve para asignar alertas.
     */
    java.util.List<UUID> activeUsersWithRole(Role role, UUID programId);

    /** Enlace de la agenda de citas del docente (https, dominio permitido), si lo registró. */
    Optional<String> appointmentLink(UUID userId);

    /** Nombre visible de un usuario del personal (p. ej. el docente en el portal del estudiante). */
    Optional<String> displayName(UUID userId);

    /** Desactiva la cuenta (supresión de datos del titular). */
    void deactivate(UUID userId);

    /** Usuario con ese correo (se busca por blind index; el correo no se guarda en claro). */
    Optional<UUID> findIdByEmail(String email);

    /** Usuario cuyo correo tiene ese blind index (permite enlazar con la identidad del estudiante). */
    Optional<UUID> findIdByEmailIndex(String emailBlindIndex);

    /** Blind index del correo del usuario (HMAC, no reversible). */
    Optional<String> emailBlindIndexOf(UUID userId);

    /** Otorga un rol de forma programática (p. ej. ESTUDIANTE al vincularse con su registro). */
    void grantRole(UUID userId, Role role, UUID grantedBy);

    /**
     * Crea o actualiza personal por correo (clave natural), p. ej. docentes importados. Solo agrega
     * el rol indicado; nunca quita roles existentes.
     */
    StaffUpsert upsertStaff(String email, String displayName, Role role, UUID grantedBy);

    /**
     * @param userId  usuario creado o existente
     * @param outcome creado, actualizado o sin cambios
     */
    record StaffUpsert(UUID userId, UpsertOutcome outcome) {
    }
}
