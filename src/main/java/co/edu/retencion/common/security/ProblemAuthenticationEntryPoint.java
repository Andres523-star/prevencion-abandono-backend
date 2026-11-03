package co.edu.retencion.common.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import co.edu.retencion.common.error.ProblemCatalog;
import co.edu.retencion.common.error.ProblemResponseWriter;

/**
 * Respuesta 401 en formato ProblemDetail cuando falta el token o es inválido/vencido.
 * No se revela el motivo exacto del rechazo del token.
 */
@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ProblemResponseWriter writer;

    public ProblemAuthenticationEntryPoint(ProblemResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        writer.write(request, response, ProblemCatalog.UNAUTHORIZED, "Token ausente, inválido o vencido");
    }
}
