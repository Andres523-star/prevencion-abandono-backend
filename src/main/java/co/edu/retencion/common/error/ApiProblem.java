package co.edu.retencion.common.error;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Descripción de un tipo de error de la API según RFC 7807 (ProblemDetail).
 *
 * <p><b>Patrón creacional: Prototype.</b> El {@link ProblemCatalog} guarda una instancia
 * "plantilla" por cada código de error (tipo, título y estado ya configurados). Cada vez que
 * ocurre un error se clona la plantilla con {@link #copy()} y solo se personaliza el detalle y
 * las propiedades del caso concreto. Así el título y el tipo de cada error quedan definidos en
 * un único lugar y nunca se mutan las plantillas compartidas.
 */
public final class ApiProblem {

    private static final String TYPE_PREFIX = "urn:retencion:problem:";

    private final String code;
    private final HttpStatus status;
    private final String title;
    private String detail;
    private final Map<String, Object> properties;

    public ApiProblem(String code, HttpStatus status, String title) {
        this(code, status, title, null, new LinkedHashMap<>());
    }

    private ApiProblem(String code, HttpStatus status, String title, String detail, Map<String, Object> properties) {
        this.code = code;
        this.status = status;
        this.title = title;
        this.detail = detail;
        this.properties = properties;
    }

    /** Clon independiente: modificar la copia nunca altera el prototipo del catálogo. */
    public ApiProblem copy() {
        return new ApiProblem(code, status, title, detail, new LinkedHashMap<>(properties));
    }

    public ApiProblem detail(String detail) {
        this.detail = detail;
        return this;
    }

    public ApiProblem property(String name, Object value) {
        this.properties.put(name, value);
        return this;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public String detail() {
        return detail;
    }

    /** Convierte a la representación estándar de Spring para serializar como application/problem+json. */
    public ProblemDetail toProblemDetail(URI instance) {
        ProblemDetail pd = ProblemDetail.forStatus(status);
        pd.setType(URI.create(TYPE_PREFIX + code));
        pd.setTitle(title);
        pd.setDetail(detail != null ? detail : title);
        pd.setInstance(instance);
        pd.setProperty("code", code);
        properties.forEach(pd::setProperty);
        return pd;
    }
}
