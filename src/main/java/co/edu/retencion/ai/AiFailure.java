package co.edu.retencion.ai;

/** Motivo por el que no hubo salida de IA y se usó el motor de reglas (fallback). */
public enum AiFailure {
    /** Interruptor {@code ai.enabled} apagado. */
    DISABLED,
    /** Sin clave de API configurada. */
    NOT_CONFIGURED,
    /** Presupuesto diario de tokens agotado. */
    BUDGET_EXCEEDED,
    /** Circuit breaker abierto tras fallos repetidos. */
    CIRCUIT_OPEN,
    /** HTTP 429 del proveedor o límite local de llamadas. */
    RATE_LIMITED,
    TIMEOUT,
    /** 5xx o error de red. */
    UNAVAILABLE,
    /** JSON inválido o que no cumple el esquema / cita recursos inexistentes, tras los reintentos. */
    INVALID_OUTPUT,
    /** El modelo declinó responder ({@code stop_reason = refusal}). */
    REFUSAL,
    /** El payload violó la lista blanca o contenía PII (no se envió nada). */
    POLICY_VIOLATION,
    ERROR
}
