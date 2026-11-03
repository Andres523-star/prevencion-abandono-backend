package co.edu.retencion.common.ratelimit;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

import co.edu.retencion.common.error.ProblemCatalog;
import co.edu.retencion.common.error.ProblemResponseWriter;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.common.web.ClientIpResolver;

/**
 * Aplica tres cubos por petición: por IP, por usuario (si hay sesión) y uno estricto por IP en
 * rutas sensibles. Si alguno se agota responde 429 con {@code Retry-After}.
 *
 * <p>No es un bean: lo instancia {@code SecurityConfig} para colocarlo dentro de la cadena de
 * seguridad, después de autenticar el JWT.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final RateLimitProperties properties;
    private final ClientIpResolver clientIpResolver;
    private final ProblemResponseWriter problemWriter;

    public RateLimitFilter(RateLimiter rateLimiter, RateLimitProperties properties,
                           ClientIpResolver clientIpResolver, ProblemResponseWriter problemWriter) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.clientIpResolver = clientIpResolver;
        this.problemWriter = problemWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled() || request.getRequestURI().startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = clientIpResolver.resolve(request);
        RateLimitDecision decision = rateLimiter.tryConsume("ip:" + ip, properties.ipPolicy());

        if (decision.allowed() && isSensitive(request.getRequestURI())) {
            decision = rateLimiter.tryConsume("sensitive:" + ip, properties.sensitivePolicy());
        }
        if (decision.allowed()) {
            var user = CurrentUser.get();
            if (user.isPresent()) {
                decision = rateLimiter.tryConsume("user:" + user.get().userId(), properties.userPolicy());
            }
        }

        response.setHeader("X-RateLimit-Remaining", Long.toString(Math.max(decision.remaining(), 0)));
        if (!decision.allowed()) {
            long seconds = Math.max(1, decision.retryAfter().toSeconds());
            response.setHeader("Retry-After", Long.toString(seconds));
            problemWriter.write(request, response, ProblemCatalog.RATE_LIMITED,
                    "Has superado el límite de solicitudes. Intenta de nuevo en " + seconds + " s.");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isSensitive(String uri) {
        return properties.sensitivePaths().stream().anyMatch(uri::startsWith);
    }
}
