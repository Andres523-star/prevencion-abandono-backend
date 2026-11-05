package co.edu.retencion.interventions.domain;

/**
 * Traduce el tipo de acción de un plan de apoyo al tipo de intervención (servicio de dominio puro).
 */
public final class PlanActionMapping {

    private PlanActionMapping() {
    }

    public static Intervention.Type typeOf(String actionType) {
        return switch (actionType == null ? "" : actionType) {
            case "NIVELACION", "TUTORIA" -> Intervention.Type.TUTORIA;
            case "REMISION_BIENESTAR" -> Intervention.Type.PSICOLOGIA;
            case "AJUSTE_HORARIO" -> Intervention.Type.FLEXIBILIZACION;
            case "APOYO_TRANSPORTE", "APOYO_ECONOMICO" -> Intervention.Type.AYUDA_ECONOMICA;
            default -> Intervention.Type.OTRO;
        };
    }
}
