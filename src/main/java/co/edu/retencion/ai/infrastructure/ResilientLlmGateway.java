package co.edu.retencion.ai.infrastructure;

import java.util.function.Supplier;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;

import co.edu.retencion.ai.AiFailure;
import co.edu.retencion.ai.application.LlmGateway;

/**
 * <b>Patrón Decorator:</b> agrega resiliencia al gateway sin tocarlo. Orden: bulkhead (concurrencia
 * máxima) → rate limiter (llamadas por minuto, protege el presupuesto y el límite del proveedor) →
 * circuit breaker (deja de llamar mientras el proveedor falla y responde de inmediato con el
 * fallback de reglas).
 *
 * <p>El circuit breaker solo cuenta fallos del proveedor (red, 5xx, 429, timeout); una salida
 * inválida del modelo no lo abre porque se valida en una capa superior.
 */
public class ResilientLlmGateway implements LlmGateway {

    private final LlmGateway delegate;
    private final CircuitBreaker circuitBreaker;
    private final RateLimiter rateLimiter;
    private final Bulkhead bulkhead;

    public ResilientLlmGateway(LlmGateway delegate, CircuitBreaker circuitBreaker, RateLimiter rateLimiter,
                               Bulkhead bulkhead) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.rateLimiter = rateLimiter;
        this.bulkhead = bulkhead;
    }

    @Override
    public Message send(String purpose, MessageCreateParams params) {
        Supplier<Message> call = () -> delegate.send(purpose, params);
        Supplier<Message> decorated = Bulkhead.decorateSupplier(bulkhead,
                RateLimiter.decorateSupplier(rateLimiter, CircuitBreaker.decorateSupplier(circuitBreaker, call)));
        try {
            return decorated.get();
        } catch (CallNotPermittedException ex) {
            throw new LlmException(AiFailure.CIRCUIT_OPEN, "Circuit breaker de IA abierto", ex);
        } catch (RequestNotPermitted | BulkheadFullException ex) {
            throw new LlmException(AiFailure.RATE_LIMITED, "Límite local de llamadas a la IA", ex);
        }
    }
}
