package co.edu.retencion.common.security;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

import co.edu.retencion.common.ratelimit.RateLimitFilter;
import co.edu.retencion.common.ratelimit.RateLimitProperties;
import co.edu.retencion.common.ratelimit.RateLimiter;
import co.edu.retencion.common.error.ProblemResponseWriter;
import co.edu.retencion.common.security.jwt.PlatformJwtAuthenticationConverter;
import co.edu.retencion.common.web.ClientIpResolver;

/**
 * Cadena de seguridad de la API: sin sesión de servidor (stateless), JWT propio como bearer,
 * cabeceras de seguridad estrictas, CORS sin comodines y rate limiting por IP y por usuario.
 *
 * <p>CSRF se desactiva porque la API no usa cookies de sesión: el token viaja en la cabecera
 * Authorization, que un sitio malicioso no puede adjuntar automáticamente.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final String API_CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";
    private static final String PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=(), payment=()";

    /**
     * Cadena aparte para Swagger UI (solo si springdoc está activo): necesita cargar scripts y
     * estilos propios, por eso su CSP es menos restrictiva que la de la API.
     */
    @Bean
    @Order(1)
    @ConditionalOnProperty(name = "springdoc.swagger-ui.enabled", havingValue = "true")
    SecurityFilterChain swaggerChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                                + "script-src 'self' 'unsafe-inline'; frame-ancestors 'none'")))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiChain(HttpSecurity http,
                                 JwtDecoder jwtDecoder,
                                 PlatformJwtAuthenticationConverter converter,
                                 ProblemAuthenticationEntryPoint entryPoint,
                                 ProblemAccessDeniedHandler accessDeniedHandler,
                                 List<PublicEndpoints> publicEndpoints,
                                 RateLimiter rateLimiter,
                                 RateLimitProperties rateLimitProperties,
                                 ClientIpResolver clientIpResolver,
                                 ProblemResponseWriter problemWriter) throws Exception {
        String[] publicPaths = publicEndpoints.stream()
                .flatMap(p -> p.patterns().stream())
                .toArray(String[]::new);

        http.csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> { })
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives(API_CSP))
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                        .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", PERMISSIONS_POLICY))
                        .addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Opener-Policy", "same-origin"))
                        .addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Resource-Policy", "same-origin")))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(publicPaths).permitAll()
                        .requestMatchers("/actuator/**").hasRole(Role.ADMIN.name())
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.decoder(jwtDecoder).jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                // El rate limit va después de autenticar para poder limitar también por usuario.
                .addFilterAfter(new RateLimitFilter(rateLimiter, rateLimitProperties, clientIpResolver, problemWriter),
                        BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
