package co.edu.permanencia.demo.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Cabeceras de seguridad en todas las respuestas, como las del backend principal.
 *
 * <ul>
 *   <li>{@code nosniff}: el navegador respeta el tipo declarado (clave para los adjuntos).</li>
 *   <li>{@code DENY} / {@code frame-ancestors 'none'}: nadie puede incrustar la API en un iframe.</li>
 *   <li>{@code no-referrer}: los enlaces firmados no se filtran por la cabecera Referer.</li>
 *   <li>{@code no-store} en las respuestas JSON de la API, que llevan datos personales.</li>
 *   <li>HSTS solo cuando la petición llegó por HTTPS (en Render, detrás de su proxy).</li>
 * </ul>
 *
 * <p>La CSP estricta ({@code default-src 'none'}) se aplica a la API JSON. Las descargas
 * ({@code /api/export/}, adjuntos del chat) no la llevan porque la ficha HTML usa estilos en línea
 * y el visor de PDF del navegador no funciona con una CSP tan cerrada.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CabecerasSeguridadFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ruta = request.getRequestURI();
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "camera=(), geolocation=(), microphone=()");
        if (request.isSecure()) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        boolean descarga = ruta.startsWith("/api/export/") || ruta.startsWith("/api/chats/adjuntos/");
        if (ruta.startsWith("/api/") && !descarga) {
            response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
            response.setHeader("Cache-Control", "no-store");
        }
        chain.doFilter(request, response);
    }
}
