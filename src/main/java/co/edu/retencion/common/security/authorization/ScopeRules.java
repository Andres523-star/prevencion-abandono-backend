package co.edu.retencion.common.security.authorization;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

import co.edu.retencion.common.security.AuthenticatedUser;
import co.edu.retencion.common.security.Role;

/**
 * Reglas ABAC por rol (una estrategia por rol). Se evalúan en {@link ScopeAuthorizationService}:
 * basta con que una regla de alguno de los roles del usuario permita el acceso.
 *
 * <ul>
 *   <li>ADMIN: todo.</li>
 *   <li>COORDINADOR y CONSEJERO: recursos de sus programas.</li>
 *   <li>PSICOLOGO: bienestar es transversal; si tiene programas asignados, solo esos.</li>
 *   <li>DOCENTE: recursos de los grupos que dicta.</li>
 *   <li>ESTUDIANTE: solo sus propios datos.</li>
 * </ul>
 */
final class ScopeRules {

    private ScopeRules() {
    }

    /** Estrategia de evaluación para un rol. */
    interface ScopeRule {

        Role role();

        boolean permits(AuthenticatedUser user, ResourceScope scope, AccessAction action, Set<UUID> taughtGroups);
    }

    static final ScopeRule ADMIN = rule(Role.ADMIN, (user, scope, action, groups) -> true);

    static final ScopeRule COORDINADOR = rule(Role.COORDINADOR,
            (user, scope, action, groups) -> intersects(user.programIds(), scope.programIds()));

    static final ScopeRule CONSEJERO = rule(Role.CONSEJERO,
            (user, scope, action, groups) -> intersects(user.programIds(), scope.programIds()));

    static final ScopeRule PSICOLOGO = rule(Role.PSICOLOGO,
            (user, scope, action, groups) -> user.programIds().isEmpty()
                    || intersects(user.programIds(), scope.programIds()));

    static final ScopeRule DOCENTE = rule(Role.DOCENTE,
            (user, scope, action, groups) -> intersects(groups, scope.groupIds()));

    static final ScopeRule ESTUDIANTE = rule(Role.ESTUDIANTE,
            (user, scope, action, groups) -> user.userId().equals(scope.ownerUserId()));

    static final Set<ScopeRule> ALL = Set.of(ADMIN, COORDINADOR, CONSEJERO, PSICOLOGO, DOCENTE, ESTUDIANTE);

    private static boolean intersects(Set<UUID> a, Set<UUID> b) {
        return !a.isEmpty() && !b.isEmpty() && !Collections.disjoint(a, b);
    }

    @FunctionalInterface
    private interface Check {
        boolean test(AuthenticatedUser user, ResourceScope scope, AccessAction action, Set<UUID> taughtGroups);
    }

    private static ScopeRule rule(Role role, Check check) {
        return new ScopeRule() {
            @Override
            public Role role() {
                return role;
            }

            @Override
            public boolean permits(AuthenticatedUser user, ResourceScope scope, AccessAction action, Set<UUID> groups) {
                return check.test(user, scope, action, groups);
            }
        };
    }
}
