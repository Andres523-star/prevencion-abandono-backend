package co.edu.retencion.common.error;

import java.io.IOException;
import java.net.URI;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * Escribe un ProblemDetail directamente en la respuesta. Lo usan los filtros y manejadores de
 * seguridad, que se ejecutan fuera de los controladores y no pasan por {@code @RestControllerAdvice}.
 */
@Component
public class ProblemResponseWriter {

    private final ProblemCatalog catalog;
    private final ObjectMapper objectMapper;

    public ProblemResponseWriter(ProblemCatalog catalog, ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ApiProblem problem) throws IOException {
        ProblemDetail body = problem.toProblemDetail(URI.create(request.getRequestURI()));
        response.setStatus(problem.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    public void write(HttpServletRequest request, HttpServletResponse response, String code, String detail) throws IOException {
        write(request, response, catalog.create(code).detail(detail));
    }
}
