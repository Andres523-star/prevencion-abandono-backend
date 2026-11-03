package co.edu.retencion.users;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import co.edu.retencion.common.security.Role;

/**
 * Puerta de entrada pública al módulo de usuarios. La usa el módulo auth para vincular
 * identidades de Supabase Auth y abrir sesiones.
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
}
