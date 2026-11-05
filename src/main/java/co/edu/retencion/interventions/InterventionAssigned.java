package co.edu.retencion.interventions;

import java.util.UUID;

/**
 * Evento: se asignó una intervención a un responsable (la campanita le avisa).
 *
 * @param interventionId intervención
 * @param studentId      estudiante
 * @param responsibleId  responsable
 * @param type           tipo de intervención
 */
public record InterventionAssigned(UUID interventionId, UUID studentId, UUID responsibleId, String type) {
}
