package co.edu.retencion.records;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Registro de asistencia con su estado de justificación.
 *
 * @param recordId              registro de asistencia
 * @param termId                periodo (clave de partición)
 * @param sessionId             sesión
 * @param enrollmentId          inscripción
 * @param studentId             estudiante
 * @param groupId               grupo
 * @param sessionDate           fecha de la sesión
 * @param status                PRESENTE, FALTA o TARDANZA
 * @param justification         estado de la justificación
 * @param justificationDeadline último día para justificar
 */
public record AbsenceInfo(UUID recordId, UUID termId, UUID sessionId, UUID enrollmentId, UUID studentId, UUID groupId,
                          LocalDate sessionDate, AttendanceStatus status, JustificationState justification,
                          LocalDate justificationDeadline) {

    public boolean isAbsence() {
        return status == AttendanceStatus.FALTA;
    }
}
