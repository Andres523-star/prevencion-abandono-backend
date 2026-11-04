package co.edu.retencion.risk.domain;

import java.util.EnumSet;
import java.util.Set;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.risk.domain.Factor.FactorCategory;

/**
 * Mínimo necesario por rol sobre los resultados del motor (CLAUDE.md, reglas 5 y 12):
 * <ul>
 *   <li>DOCENTE: solo factores académicos y de asistencia (notas y faltas), nunca texto de la IA.</li>
 *   <li>COORDINADOR: sin factores socioeconómicos ni de bienestar; texto de la IA solo si el payload no
 *       llevó señales sensibles.</li>
 *   <li>CONSEJERO y ADMIN: todo salvo bienestar/salud.</li>
 *   <li>PSICOLOGO: todo.</li>
 * </ul>
 * Un usuario con varios roles ve la unión de lo que cada rol permite.
 */
public final class FactorVisibility {

    private FactorVisibility() {
    }

    public static Set<FactorCategory> categoriesFor(Set<Role> roles) {
        Set<FactorCategory> visible = EnumSet.noneOf(FactorCategory.class);
        if (roles.contains(Role.DOCENTE)) {
            visible.addAll(EnumSet.of(FactorCategory.ACADEMICO, FactorCategory.ASISTENCIA));
        }
        if (roles.contains(Role.COORDINADOR)) {
            visible.addAll(EnumSet.of(FactorCategory.ACADEMICO, FactorCategory.ASISTENCIA, FactorCategory.CONDUCTA,
                    FactorCategory.CAUSAS));
        }
        if (roles.contains(Role.CONSEJERO) || roles.contains(Role.ADMIN)) {
            visible.addAll(EnumSet.complementOf(EnumSet.of(FactorCategory.BIENESTAR)));
        }
        if (roles.contains(Role.PSICOLOGO)) {
            visible.addAll(EnumSet.allOf(FactorCategory.class));
        }
        return visible;
    }

    /** ¿Puede ver el texto libre generado por la IA (resumen, causas, recomendaciones)? */
    public static boolean canSeeAiText(Set<Role> roles, boolean sensitiveContext) {
        if (roles.contains(Role.PSICOLOGO)) {
            return true;
        }
        if (sensitiveContext) {
            return false;
        }
        return roles.contains(Role.ADMIN) || roles.contains(Role.CONSEJERO) || roles.contains(Role.COORDINADOR);
    }

    /** Las causas probables de categoría SALUD solo las ve PSICOLOGO. */
    public static boolean canSeeHealthCauses(Set<Role> roles) {
        return roles.contains(Role.PSICOLOGO);
    }
}
