package co.edu.retencion.auth.infrastructure.supabase;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import co.edu.retencion.auth.domain.AuthProblems;
import co.edu.retencion.auth.infrastructure.supabase.SupabaseAuthClient.SupabaseUser;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.users.FederatedIdentity;

/**
 * <b>Patrón estructural: Adapter.</b> Traduce el modelo de Supabase Auth (claims {@code sub},
 * {@code email}, {@code aal}, {@code user_metadata} y la respuesta de {@code /auth/v1/user}) al
 * modelo interno {@link ExternalIdentity}. Cambiar de proveedor (Auth0, Keycloak, Entra ID)
 * significaría escribir otro adaptador, sin tocar usuarios ni sesiones.
 */
@Component
public class SupabaseIdentityAdapter {

    /**
     * Identidad externa normalizada.
     *
     * @param identity      datos para vincular/crear el usuario interno
     * @param emailVerified si Supabase confirmó el correo
     * @param aal           nivel de aseguramiento de la autenticación ({@code aal1} o {@code aal2} = MFA)
     */
    public record ExternalIdentity(FederatedIdentity identity, boolean emailVerified, String aal) {
    }

    public ExternalIdentity adapt(Jwt token, SupabaseUser user) {
        if (!token.getSubject().equals(user.id())) {
            throw new DomainException(AuthProblems.INVALID_PROVIDER_TOKEN, "El token no corresponde al usuario");
        }
        // El correo de /auth/v1/user es el autoritativo; el del JWT es el respaldo.
        String email = Optional.ofNullable(user.email()).or(() -> Optional.ofNullable(token.getClaimAsString("email")))
                .filter(e -> e.contains("@"))
                .map(e -> e.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new DomainException(AuthProblems.INVALID_PROVIDER_TOKEN,
                        "La cuenta de Supabase no tiene correo"));
        String name = displayName(user.userMetadata()).orElse(email);
        String aal = Optional.ofNullable(token.getClaimAsString("aal")).orElse("aal1");
        return new ExternalIdentity(
                new FederatedIdentity(token.getIssuer().toString(), token.getSubject(), email, name),
                user.emailConfirmedAt() != null,
                aal);
    }

    private static Optional<String> displayName(Map<String, Object> metadata) {
        if (metadata == null) {
            return Optional.empty();
        }
        for (String key : new String[] {"full_name", "name"}) {
            Object value = metadata.get(key);
            if (value instanceof String s && !s.isBlank()) {
                return Optional.of(s.trim());
            }
        }
        return Optional.empty();
    }
}
