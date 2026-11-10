package co.edu.retencion.risk;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Planes de apoyo para el módulo de intervenciones: un consejero revisa el plan, lo edita, lo
 * aprueba y lo convierte en intervenciones.
 */
public interface SupportPlans {

    Optional<PlanSummary> find(UUID planId);

    /**
     * Reemplaza las acciones por las editadas y aprueba el plan (solo si está PROPUESTO).
     *
     * @throws co.edu.retencion.common.error.DomainException si ya fue revisado
     */
    PlanSummary approveWithActions(UUID planId, List<PlanItem> actions, String comment, UUID reviewer);

    /**
     * @param status PROPUESTO, APROBADO, RECHAZADO o REEMPLAZADO
     */
    /**
     * Planes APROBADOS por una persona, con el texto ya escrito para el estudiante (marcadores rellenos con
     * su nombre y la materia). Sin nivel, puntaje ni factores: es lo que ve el propio estudiante.
     */
    java.util.List<StudentPlan> approvedForStudent(UUID studentId);

    record StudentPlan(UUID id, String subjectName, String message, java.util.List<String> resourceIds, Instant approvedAt) {
    }

    record PlanSummary(UUID id, UUID studentId, UUID enrollmentId, UUID groupId, String triggerLevel, String status,
                       List<PlanItem> actions, Instant createdAt) {
    }

    /**
     * Acción del plan (mismos campos que el plan propuesto).
     */
    record PlanItem(String type, String title, String description, String priority, String suggestedOwner,
                    String resourceId, int dueInDays) {
    }
}
