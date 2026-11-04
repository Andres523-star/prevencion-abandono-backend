package co.edu.retencion.ai.application;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;

import co.edu.retencion.ai.AiFailure;

/**
 * Puerto hacia el proveedor de modelos. La cadena real es
 * {@code presupuesto → resiliencia (circuit breaker, rate limiter, bulkhead) → adaptador Anthropic},
 * armada con decoradores en {@code AiConfig}.
 */
public interface LlmGateway {

    /**
     * @param purpose propósito (RISK_EXPLAIN, SUPPORT_PLAN, RETENTION_AGENT) para métricas
     * @throws LlmException si no hubo respuesta utilizable del proveedor
     */
    Message send(String purpose, MessageCreateParams params);

    /** Fallo de la llamada con su motivo normalizado; nunca incluye el contenido. */
    class LlmException extends RuntimeException {

        private final AiFailure reason;

        public LlmException(AiFailure reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        public LlmException(AiFailure reason, String message) {
            this(reason, message, null);
        }

        public AiFailure reason() {
            return reason;
        }
    }
}
