package co.edu.retencion.common.security.authorization;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exige que el usuario actual tenga alcance sobre el recurso indicado.
 *
 * <pre>{@code
 * @RequireScope(resource = "USER", id = "#id")
 * public UserDetailView get(UUID id) { ... }
 * }</pre>
 *
 * <p>Se aplica mediante un proxy AOP ({@link RequireScopeAspect}).
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireScope {

    /** Tipo de recurso registrado por algún {@link ResourceScopeResolver}. */
    String resource();

    /** Expresión SpEL sobre los parámetros que devuelve el UUID del recurso. */
    String id();

    AccessAction action() default AccessAction.READ;
}
