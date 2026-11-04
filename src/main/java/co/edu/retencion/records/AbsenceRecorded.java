package co.edu.retencion.records;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.modulith.events.Externalized;

/**
 * Evento: se registró una falta. Lo consume el módulo de correo (aviso al estudiante después de la
 * ventana de edición). Sin datos personales: solo ids y hechos académicos. Con RabbitMQ activo se
 * publica también en el exchange {@code retencion.events}.
 *
 * @param recordId              registro de asistencia
 * @param enrollmentId          inscripción
 * @param studentId             estudiante
 * @param groupId               grupo
 * @param subjectName           nombre de la materia
 * @param sessionDate           fecha de la sesión
 * @param justificationDeadline último día para justificar
 * @param editableUntil         fin de la ventana de edición del docente
 */
@Externalized("retencion.events::attendance.absence-recorded")
public record AbsenceRecorded(UUID recordId, UUID enrollmentId, UUID studentId, UUID groupId, String subjectName,
                              LocalDate sessionDate, LocalDate justificationDeadline, Instant editableUntil) {
}
