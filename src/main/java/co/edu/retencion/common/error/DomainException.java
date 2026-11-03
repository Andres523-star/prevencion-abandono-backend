package co.edu.retencion.common.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Excepción base de negocio. Lleva el código del {@link ProblemCatalog} y un detalle legible
 * para el usuario; el manejador global la convierte en ProblemDetail.
 *
 * <p>El mensaje NUNCA debe incluir datos personales (se registra en logs).
 */
public class DomainException extends RuntimeException {

    private final String problemCode;
    private final Map<String, Object> properties;

    public DomainException(String problemCode, String detail) {
        this(problemCode, detail, Map.of());
    }

    public DomainException(String problemCode, String detail, Map<String, Object> properties) {
        super(detail);
        this.problemCode = problemCode;
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    public String problemCode() {
        return problemCode;
    }

    public Map<String, Object> properties() {
        return properties;
    }

    public static DomainException notFound(String detail) {
        return new DomainException(ProblemCatalog.NOT_FOUND, detail);
    }

    public static DomainException conflict(String detail) {
        return new DomainException(ProblemCatalog.CONFLICT, detail);
    }

    public static DomainException badRequest(String detail) {
        return new DomainException(ProblemCatalog.BAD_REQUEST, detail);
    }
}
