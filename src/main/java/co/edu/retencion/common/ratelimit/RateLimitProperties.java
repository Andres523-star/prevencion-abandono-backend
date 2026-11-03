package co.edu.retencion.common.ratelimit;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Límites de peticiones ({@code app.rate-limit.*}).
 *
 * @param enabled   activa o desactiva el filtro
 * @param perIp     límite general por IP
 * @param perUser   límite por usuario autenticado
 * @param sensitive límite estricto por IP para rutas sensibles (login, refresh)
 * @param sensitivePaths prefijos de ruta a los que aplica el límite estricto
 */
@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue Limit perIp,
        @DefaultValue Limit perUser,
        @DefaultValue Limit sensitive,
        @DefaultValue("/api/v1/auth/") List<String> sensitivePaths) {

    /**
     * @param capacity peticiones por periodo
     * @param period   periodo de recarga
     */
    public record Limit(@DefaultValue("300") long capacity, @DefaultValue("1m") Duration period) {
    }

    public RateLimitPolicy ipPolicy() {
        return new RateLimitPolicy("ip", perIp.capacity(), perIp.period());
    }

    public RateLimitPolicy userPolicy() {
        return new RateLimitPolicy("user", perUser.capacity(), perUser.period());
    }

    public RateLimitPolicy sensitivePolicy() {
        return new RateLimitPolicy("sensitive", sensitive.capacity(), sensitive.period());
    }
}
