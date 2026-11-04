package co.edu.retencion.records.application;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.common.security.AuthenticatedUser;
import co.edu.retencion.common.security.Role;

/**
 * Mínimo necesario por rol en las lecturas: un usuario que es solo DOCENTE ve únicamente los datos
 * de los grupos que dicta, aunque tenga acceso al estudiante (que puede estar en otras materias).
 */
@Component
class RecordsAccess {

    private static final Set<Role> BROAD = Set.of(Role.ADMIN, Role.COORDINADOR, Role.CONSEJERO, Role.PSICOLOGO,
            Role.ESTUDIANTE);

    private final AcademicDirectory academic;

    RecordsAccess(AcademicDirectory academic) {
        this.academic = academic;
    }

    /** Vacío = sin restricción por grupo; presente = solo esos grupos. */
    Optional<Set<UUID>> teacherOnlyGroups(AuthenticatedUser user) {
        boolean broad = user.roles().stream().anyMatch(BROAD::contains);
        return broad ? Optional.empty() : Optional.of(academic.groupsTaughtBy(user.userId()));
    }

    boolean isPrivileged(AuthenticatedUser user) {
        return user.hasRole(Role.ADMIN) || user.hasRole(Role.COORDINADOR);
    }
}
