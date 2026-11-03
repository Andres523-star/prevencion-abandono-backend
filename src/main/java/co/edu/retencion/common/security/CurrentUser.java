package co.edu.retencion.common.security;

import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.error.ProblemCatalog;

/**
 * Acceso estático al usuario autenticado de la petición en curso.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Optional<AuthenticatedUser> get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    public static AuthenticatedUser require() {
        return get().orElseThrow(() -> new DomainException(ProblemCatalog.UNAUTHORIZED, "Se requiere una sesión válida"));
    }
}
