package co.edu.retencion.common.security.jwt;

import java.time.Duration;
import java.util.Base64;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración del JWT de acceso propio de la plataforma ({@code app.security.jwt.*}).
 *
 * @param issuer         emisor ({@code iss})
 * @param audience       audiencia ({@code aud})
 * @param secret         secreto HMAC en Base64 (mínimo 256 bits). Viene de variable de entorno.
 * @param accessTokenTtl vida del token de acceso (10-15 min según CLAUDE.md)
 * @param clockSkew      tolerancia de reloj al validar
 */
@Validated
@ConfigurationProperties("app.security.jwt")
public record PlatformJwtProperties(
        @DefaultValue("retencion-backend") @NotBlank String issuer,
        @DefaultValue("retencion-api") @NotBlank String audience,
        @NotBlank String secret,
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("30s") Duration clockSkew) {

    public PlatformJwtProperties {
        if (accessTokenTtl.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("El token de acceso no puede durar más de 15 minutos");
        }
    }

    /** Clave decodificada; falla al arrancar si es demasiado corta. */
    public byte[] secretBytes() {
        byte[] key = Base64.getDecoder().decode(secret.trim());
        if (key.length < 32) {
            throw new IllegalStateException("APP_JWT_SECRET debe tener al menos 256 bits (32 bytes en Base64)");
        }
        return key;
    }
}
