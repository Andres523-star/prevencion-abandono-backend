package co.edu.retencion.retention;

import java.util.UUID;

/**
 * Evento: vence un seguimiento (7/15/30 días) o un contacto de la campaña de reingreso. La campanita
 * le recuerda al consejero; nunca se envía nada al estudiante automáticamente.
 *
 * @param caseId     caso
 * @param studentId  estudiante
 * @param counselor  consejero responsable
 * @param kind       SEGUIMIENTO o REINGRESO
 * @param offsetDays días desde la entrada a seguimiento (0 en reingreso)
 */
public record RetentionFollowUpDue(UUID caseId, UUID studentId, UUID counselor, String kind, int offsetDays) {
}
