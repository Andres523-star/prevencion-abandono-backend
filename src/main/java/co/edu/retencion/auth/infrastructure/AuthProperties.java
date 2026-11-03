package co.edu.retencion.auth.infrastructure;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración de autenticación ({@code app.auth.*}). URL y claves de Supabase llegan por
 * variables de entorno (ver .env.example y docs/supabase-auth.md).
 *
 * @param allowedEmailDomains dominios permitidos; <b>vacío = cualquier correo</b>
 * @param refreshTokenTtl     vida de cada refresh token (inactividad máxima)
 * @param sessionMaxLifetime  vida absoluta de la sesión aunque se renueve
 * @param supabase            parámetros del proyecto de Supabase Auth
 * @param breakGlass          cuenta local de emergencia
 */
@ConfigurationProperties("app.auth")
public record AuthProperties(
        @DefaultValue List<String> allowedEmailDomains,
        @DefaultValue("8h") Duration refreshTokenTtl,
        @DefaultValue("24h") Duration sessionMaxLifetime,
        @DefaultValue Supabase supabase,
        @DefaultValue BreakGlass breakGlass) {

    /**
     * @param url                  URL del proyecto, p. ej. {@code https://abcd.supabase.co}
     * @param anonKey              clave pública (anon / publishable) del proyecto; no es secreta
     * @param jwtSecret            secreto JWT heredado (HS256); vacío = se validan firmas con el JWKS público
     * @param audience             audiencia de los tokens de usuarios autenticados
     * @param requireVerifiedEmail exige correo confirmado (recomendado)
     * @param mfaAal               nivel de aseguramiento que cuenta como MFA en Supabase
     */
    public record Supabase(
            String url,
            String anonKey,
            String jwtSecret,
            @DefaultValue("authenticated") String audience,
            @DefaultValue("true") boolean requireVerifiedEmail,
            @DefaultValue("aal2") String mfaAal) {

        public boolean isConfigured() {
            return url != null && !url.isBlank() && anonKey != null && !anonKey.isBlank();
        }

        /** Emisor de los tokens: {@code <url>/auth/v1}. */
        public String issuer() {
            String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            return base + "/auth/v1";
        }

        public String jwksUri() {
            return issuer() + "/.well-known/jwks.json";
        }

        public String userEndpoint() {
            return issuer() + "/user";
        }

        @Override
        public String toString() {
            return "Supabase[url=" + url + "]";
        }
    }

    /**
     * @param enabled      habilita el login local de emergencia
     * @param username     usuario de la cuenta
     * @param passwordHash hash argon2id ({@code $argon2id$...}); generar con BreakGlassSecretGenerator
     * @param totpSecret   secreto TOTP en Base32
     */
    public record BreakGlass(@DefaultValue("false") boolean enabled, String username, String passwordHash,
                             String totpSecret) {

        @Override
        public String toString() {
            return "BreakGlass[enabled=" + enabled + "]";
        }
    }

    public boolean isAllowedEmail(String email) {
        int at = email.lastIndexOf('@');
        if (at < 1) {
            return false;
        }
        if (allowedEmailDomains.isEmpty()) {
            return true;
        }
        String domain = email.substring(at + 1).toLowerCase(Locale.ROOT);
        return allowedEmailDomains.stream().anyMatch(d -> d.trim().equalsIgnoreCase(domain));
    }
}
