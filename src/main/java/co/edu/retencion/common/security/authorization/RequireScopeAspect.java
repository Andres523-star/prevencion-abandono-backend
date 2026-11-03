package co.edu.retencion.common.security.authorization;

import java.util.UUID;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;

import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

/**
 * Aplica {@link RequireScope}. Spring envuelve el bean en un proxy y este aspecto decide si
 * la llamada llega al objeto real (control de acceso típico del patrón Proxy).
 *
 * <p>Su orden es posterior al de la auditoría para que una denegación quede registrada.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class RequireScopeAspect {

    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer parameterNames = new DefaultParameterNameDiscoverer();
    private final ScopeAuthorizationService abac;

    public RequireScopeAspect(ScopeAuthorizationService abac) {
        this.abac = abac;
    }

    @Around("@annotation(requireScope)")
    public Object enforce(ProceedingJoinPoint pjp, RequireScope requireScope) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                pjp.getTarget(), signature.getMethod(), pjp.getArgs(), parameterNames);
        Expression expression = parser.parseExpression(requireScope.id());
        Object value = expression.getValue(context);
        UUID resourceId = value instanceof UUID uuid ? uuid : value != null ? UUID.fromString(value.toString()) : null;
        abac.check(requireScope.resource(), resourceId, requireScope.action());
        return pjp.proceed();
    }
}
