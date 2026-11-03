package co.edu.retencion.auth.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.Role;

/**
 * Regla no negociable: los roles con acceso a datos sensibles (ADMIN, CONSEJERO, PSICOLOGO,
 * COORDINADOR) solo pueden abrir o renovar sesión si la autenticación incluyó MFA.
 */
public final class MfaPolicy {

    private MfaPolicy() {
    }

    public static void enforce(Set<Role> roles, boolean mfaSatisfied) {
        List<String> needing = roles.stream().filter(Role::requiresMfa).map(Enum::name).sorted().toList();
        if (!needing.isEmpty() && !mfaSatisfied) {
            throw new DomainException(AuthProblems.MFA_REQUIRED,
                    "Tu rol (" + String.join(", ", needing) + ") requiere autenticación multifactor. "
                            + "Activa la verificación en dos pasos (app autenticadora) en tu cuenta e ingresa de nuevo.",
                    Map.of("rolesRequiringMfa", needing));
        }
    }
}
