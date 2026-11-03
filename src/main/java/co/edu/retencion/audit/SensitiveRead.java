package co.edu.retencion.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca una lectura de datos personales o sensibles. Cada ejecución queda en la auditoría con
 * usuario, IP y motivo. El motivo llega en la cabecera {@code X-Access-Reason}.
 *
 * <p>Si la auditoría no se puede escribir, la lectura falla (no se entregan datos sin rastro).
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SensitiveRead {

    /** Tipo de dato leído, p. ej. {@code UserIdentity}. */
    String entity();

    /** SpEL para el id del registro leído. */
    String entityId() default "";

    /** Si es {@code true}, la petición sin motivo se rechaza con 400. */
    boolean requireReason() default true;
}
