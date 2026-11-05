package co.edu.retencion.analytics;

import java.util.Set;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;

import co.edu.retencion.common.security.AuthenticatedUser;
import co.edu.retencion.common.security.Role;

/**
 * Alcance de quien consulta, explícito porque un reporte se genera en segundo plano, sin petición.
 *
 * @param programs programas visibles; {@code null} = todos
 */
public record AnalyticsViewer(Set<UUID> programs) {

    public AnalyticsViewer {
        programs = programs == null ? null : Set.copyOf(programs);
    }

    public static AnalyticsViewer all() {
        return new AnalyticsViewer(null);
    }

    public static AnalyticsViewer of(Set<UUID> programs) {
        return new AnalyticsViewer(programs);
    }

    /**
     * Alcance de un usuario: ADMIN ve todo, el psicólogo sin programas es transversal y los demás solo
     * ven sus programas.
     *
     * @throws AccessDeniedException si no tiene programas asignados
     */
    public static AnalyticsViewer of(AuthenticatedUser user) {
        if (user.hasRole(Role.ADMIN) || (user.hasRole(Role.PSICOLOGO) && user.programIds().isEmpty())) {
            return all();
        }
        if (user.programIds().isEmpty()) {
            throw new AccessDeniedException("No tienes programas asignados");
        }
        return of(user.programIds());
    }

    /**
     * Programas que aplican a la consulta ({@code null} = todos).
     *
     * @throws AccessDeniedException si pide un programa fuera de su alcance
     */
    public Set<UUID> effective(UUID requested) {
        if (requested == null) {
            return programs;
        }
        if (programs != null && !programs.contains(requested)) {
            throw new AccessDeniedException("Programa fuera de tu alcance");
        }
        return Set.of(requested);
    }
}
