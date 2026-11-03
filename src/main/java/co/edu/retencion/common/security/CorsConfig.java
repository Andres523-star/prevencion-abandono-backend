package co.edu.retencion.common.security;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS estricto: solo los orígenes listados en {@code APP_CORS_ALLOWED_ORIGINS}. Un comodín
 * {@code *} hace fallar el arranque a propósito.
 */
@Configuration(proxyBeanMethods = false)
public class CorsConfig {

    @ConfigurationProperties("app.security.cors")
    public record CorsProperties(@DefaultValue List<String> allowedOrigins) {

        public CorsProperties {
            if (allowedOrigins.stream().anyMatch(o -> o.contains("*"))) {
                throw new IllegalArgumentException("CORS: no se permiten comodines en app.security.cors.allowed-origins");
            }
        }
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id", "X-Access-Reason", "If-Match"));
        config.setExposedHeaders(List.of("X-Request-Id", "Retry-After", "ETag", "X-RateLimit-Remaining"));
        // Sin cookies: el token va en Authorization.
        config.setAllowCredentials(false);
        config.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
