package co.edu.retencion.audit.application;

import java.util.Map;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import co.edu.retencion.audit.AuditOutcome;
import co.edu.retencion.audit.Audited;
import co.edu.retencion.audit.SensitiveRead;
import co.edu.retencion.common.error.DomainException;

/**
 * Aspecto (proxy AOP) que audita los métodos anotados con {@link Audited} y {@link SensitiveRead}.
 *
 * <p>Tiene la mayor precedencia entre los aspectos de seguridad: envuelve a la autorización
 * por rol y por alcance, así que también registra los accesos denegados (DENIED).
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class AuditAspect {

    public static final String SENSITIVE_READ_ACTION = "SENSITIVE_READ";
    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    private final AuditService audit;
    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer parameterNames = new DefaultParameterNameDiscoverer();

    public AuditAspect(AuditService audit) {
        this.audit = audit;
    }

    @Around("@annotation(audited)")
    public Object auditWrite(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
        Object result;
        try {
            result = pjp.proceed();
        } catch (Throwable ex) {
            audit.record(audited.action(), audited.entity(), evaluate(pjp, audited.entityId(), null),
                    outcomeOf(ex), failureDetails(ex));
            throw ex;
        }
        audit.record(audited.action(), audited.entity(), evaluate(pjp, audited.entityId(), result),
                AuditOutcome.SUCCESS, Map.of());
        return result;
    }

    @Around("@annotation(sensitiveRead)")
    public Object auditSensitiveRead(ProceedingJoinPoint pjp, SensitiveRead sensitiveRead) throws Throwable {
        String entityId = evaluate(pjp, sensitiveRead.entityId(), null);
        if (sensitiveRead.requireReason() && audit.currentReason().isEmpty()) {
            audit.record(SENSITIVE_READ_ACTION, sensitiveRead.entity(), entityId, AuditOutcome.FAILURE,
                    Map.of("error", AuditProblems.REASON_REQUIRED));
            throw new DomainException(AuditProblems.REASON_REQUIRED,
                    "Indica el motivo del acceso en la cabecera X-Access-Reason");
        }
        Object result;
        try {
            result = pjp.proceed();
        } catch (Throwable ex) {
            audit.record(SENSITIVE_READ_ACTION, sensitiveRead.entity(), entityId, outcomeOf(ex), failureDetails(ex));
            throw ex;
        }
        // Se audita ANTES de devolver los datos: si la auditoría falla, la lectura también.
        audit.record(SENSITIVE_READ_ACTION, sensitiveRead.entity(), entityId, AuditOutcome.SUCCESS, Map.of());
        return result;
    }

    private String evaluate(ProceedingJoinPoint pjp, String expression, Object result) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            MethodSignature signature = (MethodSignature) pjp.getSignature();
            MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                    pjp.getTarget(), signature.getMethod(), pjp.getArgs(), parameterNames);
            context.setVariable("result", result);
            Object value = parser.parseExpression(expression).getValue(context);
            return value == null ? null : value.toString();
        } catch (EvaluationException ex) {
            // Un error en la expresión no debe impedir auditar: se registra sin id.
            log.warn("No se pudo evaluar entityId de auditoría '{}'", expression);
            return null;
        }
    }

    private static AuditOutcome outcomeOf(Throwable ex) {
        return ex instanceof AccessDeniedException ? AuditOutcome.DENIED : AuditOutcome.FAILURE;
    }

    private static Map<String, String> failureDetails(Throwable ex) {
        String error = ex instanceof DomainException de ? de.problemCode() : ex.getClass().getSimpleName();
        return Map.of("error", error);
    }
}
