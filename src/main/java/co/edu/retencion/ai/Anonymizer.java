package co.edu.retencion.ai;

import java.util.List;

/**
 * Construye el payload que se envía a la IA a partir de hechos seudonimizados, con lista blanca de
 * campos, bandas en lugar de valores sensibles y redacción de PII del texto libre. El motor de
 * riesgo usa también el hash para detectar si algo cambió desde el último análisis.
 */
public interface Anonymizer {

    /**
     * @param facts      hechos del caso
     * @param resources  recursos del catálogo elegibles para el estudiante (la IA solo puede citar estos)
     * @param planTarget materia y nivel del plan de apoyo, o {@code null} si es un análisis general
     * @throws PayloadRejectedException si el resultado viola la lista blanca o contiene PII
     */
    AnonymizedPayload anonymize(CaseFacts facts, List<SupportResourceInfo> resources, PlanTarget planTarget);

    /**
     * Payload listo para enviar.
     *
     * @param json             JSON canónico (validado contra la lista blanca)
     * @param sha256           hash hexadecimal del JSON
     * @param sensitiveContext incluye señales de bienestar
     * @param freeTextIncluded incluye texto libre redactado
     */
    record AnonymizedPayload(String json, String sha256, boolean sensitiveContext, boolean freeTextIncluded) {

        @Override
        public String toString() {
            return "AnonymizedPayload[sha256=" + sha256 + "]";
        }
    }

    /**
     * @param subjectRef      referencia de la materia en el payload (M1...)
     * @param attendanceLevel nivel alcanzado (AVISO, CRITICO o PERDIDA)
     */
    record PlanTarget(String subjectRef, String attendanceLevel) {
    }
}
