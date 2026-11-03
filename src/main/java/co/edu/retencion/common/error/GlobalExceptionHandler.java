package co.edu.retencion.common.error;

import java.net.URI;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.common.security.SecurityDeniedEvent;
import co.edu.retencion.common.web.ClientIpResolver;

/**
 * Traduce todas las excepciones a ProblemDetail (RFC 7807) con un campo {@code code} estable
 * que el frontend puede usar para mostrar mensajes. Nunca expone trazas ni mensajes internos.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ProblemCatalog catalog;
    private final ApplicationEventPublisher events;
    private final ClientIpResolver clientIpResolver;

    public GlobalExceptionHandler(ProblemCatalog catalog, ApplicationEventPublisher events, ClientIpResolver clientIpResolver) {
        this.catalog = catalog;
        this.events = events;
        this.clientIpResolver = clientIpResolver;
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> handleDomain(DomainException ex, HttpServletRequest request) {
        ApiProblem problem = catalog.create(ex.problemCode()).detail(ex.getMessage());
        ex.properties().forEach(problem::property);
        if (problem.status().is5xxServerError()) {
            log.error("Error de dominio {}", ex.problemCode(), ex);
        }
        return respond(problem, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        events.publishEvent(new SecurityDeniedEvent(
                CurrentUser.get().map(u -> u.userId()).orElse(null),
                request.getMethod(), request.getRequestURI(), clientIpResolver.resolve(request)));
        return respond(catalog.create(ProblemCatalog.FORBIDDEN), request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return respond(catalog.create(ProblemCatalog.UNAUTHORIZED), request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<Map<String, String>> errors = ex.getConstraintViolations().stream()
                .map(v -> Map.of("field", String.valueOf(v.getPropertyPath()), "message", v.getMessage()))
                .toList();
        return respond(catalog.create(ProblemCatalog.VALIDATION_FAILED).property("errors", errors), request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleOptimisticLock(ObjectOptimisticLockingFailureException ex, HttpServletRequest request) {
        return respond(catalog.create(ProblemCatalog.CONFLICT)
                .detail("El recurso fue modificado por otra persona. Recarga e intenta de nuevo."), request);
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleIntegrity(org.springframework.dao.DataIntegrityViolationException ex,
                                                  HttpServletRequest request) {
        // Clave única o referencia violada: no se expone el mensaje de la BD (puede incluir valores).
        log.warn("Violación de integridad: {}", ex.getMostSpecificCause().getClass().getSimpleName());
        return respond(catalog.create(ProblemCatalog.CONFLICT)
                .detail("El registro entra en conflicto con datos existentes"), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        // Se registra la excepción completa (con request-id en el MDC) pero al cliente solo va un mensaje genérico.
        log.error("Error no controlado", ex);
        return respond(catalog.create(ProblemCatalog.INTERNAL_ERROR), request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
                .toList();
        ProblemDetail body = catalog.create(ProblemCatalog.VALIDATION_FAILED)
                .property("errors", errors)
                .toProblemDetail(instanceOf(request));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).headers(headers).body(body);
    }

    /**
     * Las excepciones estándar de Spring MVC (método no permitido, JSON mal formado, etc.) ya traen
     * un ProblemDetail; aquí se uniforma con nuestro tipo y código.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = catalog.create(codeFor(status))
                .toProblemDetail(instanceOf(request));
        return ResponseEntity.status(status).headers(headers).body(problem);
    }

    private static String codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> ProblemCatalog.BAD_REQUEST;
            case 401 -> ProblemCatalog.UNAUTHORIZED;
            case 403 -> ProblemCatalog.FORBIDDEN;
            case 404 -> ProblemCatalog.NOT_FOUND;
            case 405 -> ProblemCatalog.METHOD_NOT_ALLOWED;
            case 409 -> ProblemCatalog.CONFLICT;
            case 412 -> ProblemCatalog.PRECONDITION_FAILED;
            case 413 -> ProblemCatalog.PAYLOAD_TOO_LARGE;
            case 415 -> ProblemCatalog.UNSUPPORTED_MEDIA_TYPE;
            case 429 -> ProblemCatalog.RATE_LIMITED;
            case 503 -> ProblemCatalog.SERVICE_UNAVAILABLE;
            default -> status.is4xxClientError() ? ProblemCatalog.BAD_REQUEST : ProblemCatalog.INTERNAL_ERROR;
        };
    }

    private static URI instanceOf(WebRequest request) {
        String description = request.getDescription(false); // formato "uri=/ruta"
        return URI.create(description.startsWith("uri=") ? description.substring(4) : "/");
    }

    private static ResponseEntity<ProblemDetail> respond(ApiProblem problem, HttpServletRequest request) {
        return ResponseEntity.status(problem.status()).body(problem.toProblemDetail(URI.create(request.getRequestURI())));
    }
}
