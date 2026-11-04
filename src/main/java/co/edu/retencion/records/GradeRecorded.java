package co.edu.retencion.records;

import java.util.UUID;

import org.springframework.modulith.events.Externalized;

/**
 * Evento: se registró, corrigió o eliminó una nota. El motor de riesgo lo usa para reanalizar al
 * estudiante (con antirrebote: una importación de muchas notas produce un solo análisis). Sin
 * datos personales ni el valor de la nota.
 *
 * @param studentId    estudiante
 * @param groupId      grupo
 * @param enrollmentId inscripción
 */
@Externalized("retencion.events::grades.recorded")
public record GradeRecorded(UUID studentId, UUID groupId, UUID enrollmentId) {
}
