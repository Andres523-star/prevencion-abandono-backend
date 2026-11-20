package co.edu.permanencia.demo.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/**
 * Respuestas de error uniformes para el frontend.
 *
 * <p>Los errores del cliente (JSON mal formado, parámetros faltantes o de otro tipo, ruta o método
 * inexistente) responden con su propio código 4xx en lugar de un 500, y nunca devuelven la traza
 * ni el mensaje interno de la excepción: así no se filtran detalles de la implementación.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> api(ApiException ex) {
        ResponseEntity.BodyBuilder respuesta = ResponseEntity.status(ex.getStatus());
        if (ex.getRetryAfterSeconds() > 0) {
            respuesta.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
        }
        return respuesta.body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> peticionInvalida(Exception ex) {
        return ResponseEntity.badRequest().body(Map.of("error", "La solicitud no es válida"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> general(Exception ex) {
        // Errores propios de Spring (404 de ruta, 405 de método, 415...) conservan su código 4xx.
        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.resolve(errorResponse.getStatusCode().value());
            if (status != null && status.is4xxClientError()) {
                return ResponseEntity.status(status).body(Map.of("error", status.getReasonPhrase()));
            }
        }
        log.error("Error no controlado ({})", ex.getClass().getSimpleName(), ex);
        return ResponseEntity.internalServerError().body(Map.of("error", "Error interno del servidor"));
    }
}
