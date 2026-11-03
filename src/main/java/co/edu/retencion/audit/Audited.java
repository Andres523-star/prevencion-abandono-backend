package co.edu.retencion.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registra en la auditoría la ejecución de una operación de escritura, con su resultado
 * (SUCCESS, FAILURE o DENIED). Una regla ArchUnit exige esta anotación en todo endpoint
 * POST/PUT/PATCH/DELETE.
 *
 * <pre>{@code
 * @Audited(action = "USER_ROLES_UPDATE", entity = "User", entityId = "#id")
 * }</pre>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /** Código de la acción, en MAYÚSCULAS_CON_GUIONES. */
    String action();

    /** Tipo de entidad afectada. */
    String entity() default "";

    /**
     * SpEL para obtener el id de la entidad. Puede usar los parámetros ({@code #id}) o el valor
     * devuelto ({@code #result.userId}).
     */
    String entityId() default "";
}
