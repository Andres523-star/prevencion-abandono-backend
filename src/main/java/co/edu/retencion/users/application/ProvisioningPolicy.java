package co.edu.retencion.users.application;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import co.edu.retencion.common.security.Role;

/**
 * Reglas de roles iniciales al crear un usuario por primer ingreso SSO ({@code app.users.*}).
 *
 * <p>Por defecto un usuario nuevo NO tiene roles: un ADMIN se los asigna en la plataforma.
 * Excepciones configurables:
 * <ul>
 *   <li>{@code bootstrap-admin-emails}: correos que reciben ADMIN al primer ingreso (puesta en marcha).</li>
 *   <li>{@code default-roles}: rol por dominio, formato {@code dominio=ROL}
 *       (p. ej. {@code estudiantes.universidad.edu.co=ESTUDIANTE}). Nunca se admite un rol con MFA.</li>
 * </ul>
 */
@ConfigurationProperties("app.users")
public record ProvisioningPolicy(
        @DefaultValue List<String> bootstrapAdminEmails,
        @DefaultValue List<String> defaultRoles) {

    public ProvisioningPolicy {
        for (String rule : defaultRoles) {
            Role role = parseRule(rule).role();
            if (role.requiresMfa()) {
                throw new IllegalArgumentException("No se puede asignar por dominio un rol privilegiado: " + role);
            }
        }
    }

    private record DomainRule(String domain, Role role) {
    }

    public Set<Role> initialRoles(String email) {
        String normalized = email.toLowerCase(Locale.ROOT);
        Set<Role> roles = EnumSet.noneOf(Role.class);
        if (bootstrapAdminEmails.stream().anyMatch(e -> e.trim().equalsIgnoreCase(normalized))) {
            roles.add(Role.ADMIN);
        }
        String domain = normalized.substring(normalized.indexOf('@') + 1);
        defaultRoles.stream().map(ProvisioningPolicy::parseRule)
                .filter(rule -> rule.domain().equals(domain))
                .forEach(rule -> roles.add(rule.role()));
        return roles;
    }

    private static DomainRule parseRule(String rule) {
        int sep = rule.indexOf('=');
        if (sep <= 0) {
            throw new IllegalArgumentException("Regla de rol por dominio inválida (dominio=ROL): " + rule);
        }
        return new DomainRule(rule.substring(0, sep).trim().toLowerCase(Locale.ROOT),
                Role.valueOf(rule.substring(sep + 1).trim()));
    }
}
