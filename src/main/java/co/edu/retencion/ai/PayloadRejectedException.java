package co.edu.retencion.ai;

import java.util.List;

/**
 * El payload no pasó la política de privacidad (campo fuera de la lista blanca o PII detectada):
 * no se envía nada a la IA y el análisis sigue solo con el motor de reglas. El mensaje lista rutas y
 * tipos de hallazgo, nunca valores.
 */
public class PayloadRejectedException extends RuntimeException {

    private final List<String> violations;

    public PayloadRejectedException(List<String> violations) {
        super("Payload rechazado por la política de privacidad: " + violations);
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }
}
