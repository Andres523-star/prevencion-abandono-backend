package co.edu.retencion.common.error;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Registro de prototipos de error (patrón Prototype). Entrega siempre copias, nunca la
 * plantilla original.
 */
@Component
public class ProblemCatalog {

    public static final String BAD_REQUEST = "bad_request";
    public static final String VALIDATION_FAILED = "validation_failed";
    public static final String UNAUTHORIZED = "unauthorized";
    public static final String FORBIDDEN = "forbidden";
    public static final String NOT_FOUND = "not_found";
    public static final String CONFLICT = "conflict";
    public static final String PRECONDITION_FAILED = "precondition_failed";
    public static final String PAYLOAD_TOO_LARGE = "payload_too_large";
    public static final String RATE_LIMITED = "rate_limited";
    public static final String METHOD_NOT_ALLOWED = "method_not_allowed";
    public static final String UNSUPPORTED_MEDIA_TYPE = "unsupported_media_type";
    public static final String SERVICE_UNAVAILABLE = "service_unavailable";
    public static final String INTERNAL_ERROR = "internal_error";

    private final Map<String, ApiProblem> prototypes = new ConcurrentHashMap<>();

    public ProblemCatalog(List<ProblemCatalogContributor> contributors) {
        register(new ApiProblem(BAD_REQUEST, HttpStatus.BAD_REQUEST, "Solicitud inválida"));
        register(new ApiProblem(VALIDATION_FAILED, HttpStatus.BAD_REQUEST, "Los datos enviados no son válidos"));
        register(new ApiProblem(UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "Autenticación requerida"));
        register(new ApiProblem(FORBIDDEN, HttpStatus.FORBIDDEN, "No tienes permiso para esta operación"));
        register(new ApiProblem(NOT_FOUND, HttpStatus.NOT_FOUND, "Recurso no encontrado"));
        register(new ApiProblem(CONFLICT, HttpStatus.CONFLICT, "Conflicto con el estado actual del recurso"));
        register(new ApiProblem(PRECONDITION_FAILED, HttpStatus.PRECONDITION_FAILED, "La versión del recurso cambió"));
        register(new ApiProblem(PAYLOAD_TOO_LARGE, HttpStatus.PAYLOAD_TOO_LARGE, "La petición supera el tamaño permitido"));
        register(new ApiProblem(RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS, "Demasiadas solicitudes"));
        register(new ApiProblem(METHOD_NOT_ALLOWED, HttpStatus.METHOD_NOT_ALLOWED, "Método no permitido"));
        register(new ApiProblem(UNSUPPORTED_MEDIA_TYPE, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Tipo de contenido no soportado"));
        register(new ApiProblem(SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, "Servicio temporalmente no disponible"));
        register(new ApiProblem(INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "Error interno"));
        contributors.forEach(c -> c.problems().forEach(this::register));
    }

    private void register(ApiProblem prototype) {
        if (prototypes.putIfAbsent(prototype.code(), prototype) != null) {
            throw new IllegalStateException("Código de error duplicado: " + prototype.code());
        }
    }

    /** Devuelve un clon del prototipo; si el código no existe se usa el error interno genérico. */
    public ApiProblem create(String code) {
        ApiProblem prototype = prototypes.getOrDefault(code, prototypes.get(INTERNAL_ERROR));
        return prototype.copy();
    }

    public boolean contains(String code) {
        return prototypes.containsKey(code);
    }
}
