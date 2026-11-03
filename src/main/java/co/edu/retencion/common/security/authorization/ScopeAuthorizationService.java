package co.edu.retencion.common.security.authorization;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import co.edu.retencion.common.security.AuthenticatedUser;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.common.security.Role;

/**
 * Servicio de autorización por alcance (ABAC), segundo nivel después del RBAC por rol.
 *
 * <p>Se usa de tres formas:
 * <ul>
 *   <li>Anotación {@link RequireScope} en métodos de servicio o controlador.</li>
 *   <li>SpEL: {@code @PreAuthorize("@abac.canAccess('USER', #id, 'READ')")}.</li>
 *   <li>Programática: {@code abac.check("USER", id, AccessAction.READ)}.</li>
 * </ul>
 *
 * <p>Falla cerrado: si no hay resolver para el tipo o el recurso no existe, se niega.
 */
@Service("abac")
public class ScopeAuthorizationService {

    private final Map<String, ResourceScopeResolver> resolvers;
    private final TeachingAssignmentProvider teachingAssignments;

    public ScopeAuthorizationService(List<ResourceScopeResolver> resolvers,
                                     Optional<TeachingAssignmentProvider> teachingAssignments) {
        this.resolvers = resolvers.stream()
                .collect(Collectors.toUnmodifiableMap(ResourceScopeResolver::resourceType, Function.identity()));
        this.teachingAssignments = teachingAssignments.orElse(userId -> Set.of());
    }

    public boolean canAccess(String resourceType, UUID resourceId, String action) {
        return canAccess(resourceType, resourceId, AccessAction.valueOf(action));
    }

    public boolean canAccess(String resourceType, UUID resourceId, AccessAction action) {
        Optional<AuthenticatedUser> user = CurrentUser.get();
        return user.isPresent() && evaluate(user.get(), resourceType, resourceId, action);
    }

    public void check(String resourceType, UUID resourceId, AccessAction action) {
        if (!canAccess(resourceType, resourceId, action)) {
            throw new AccessDeniedException("Fuera de alcance");
        }
    }

    /** Evaluación pura (sin contexto de seguridad), útil para pruebas y procesos batch. */
    public boolean evaluate(AuthenticatedUser user, String resourceType, UUID resourceId, AccessAction action) {
        ResourceScopeResolver resolver = resolvers.get(resourceType);
        if (resolver == null || resourceId == null) {
            return false;
        }
        Optional<ResourceScope> scope = resolver.resolve(resourceId);
        if (scope.isEmpty()) {
            return false;
        }
        // Cualquier usuario puede ver su propio registro (p. ej. su perfil de usuario).
        if (user.userId().equals(scope.get().ownerUserId()) && action == AccessAction.READ) {
            return true;
        }
        Set<UUID> taughtGroups = user.hasRole(Role.DOCENTE)
                ? teachingAssignments.groupsTaughtBy(user.userId())
                : Set.of();
        return ScopeRules.ALL.stream()
                .filter(rule -> user.hasRole(rule.role()))
                .anyMatch(rule -> rule.permits(user, scope.get(), action, taughtGroups));
    }
}
