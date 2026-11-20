package co.edu.permanencia.demo.common;

import org.springframework.http.HttpStatus;

/** Error controlado que se traduce a una respuesta JSON con código HTTP. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final long retryAfterSeconds;

    public ApiException(HttpStatus status, String message) {
        this(status, message, 0);
    }

    private ApiException(HttpStatus status, String message, long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** Segundos que el cliente debe esperar antes de reintentar (cabecera Retry-After); 0 si no aplica. */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    /** Demasiados intentos: 429 con la espera sugerida. */
    public static ApiException tooManyRequests(String message, long retryAfterSeconds) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, message, Math.max(1, retryAfterSeconds));
    }
}
