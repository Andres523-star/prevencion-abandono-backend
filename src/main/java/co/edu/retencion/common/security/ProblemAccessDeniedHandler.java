package co.edu.retencion.common.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import co.edu.retencion.common.error.ProblemCatalog;
import co.edu.retencion.common.error.ProblemResponseWriter;
import co.edu.retencion.common.web.ClientIpResolver;

/**
 * Respuesta 403 en formato ProblemDetail y publicación del evento de acceso denegado para que
 * quede en la auditoría.
 */
@Component
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemResponseWriter writer;
    private final ApplicationEventPublisher events;
    private final ClientIpResolver clientIpResolver;

    public ProblemAccessDeniedHandler(ProblemResponseWriter writer, ApplicationEventPublisher events,
                                      ClientIpResolver clientIpResolver) {
        this.writer = writer;
        this.events = events;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        events.publishEvent(new SecurityDeniedEvent(
                CurrentUser.get().map(AuthenticatedUser::userId).orElse(null),
                request.getMethod(), request.getRequestURI(), clientIpResolver.resolve(request)));
        writer.write(request, response, ProblemCatalog.FORBIDDEN, "No tienes permiso para esta operación");
    }
}
