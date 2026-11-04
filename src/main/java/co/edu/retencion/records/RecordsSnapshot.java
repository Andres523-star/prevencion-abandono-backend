package co.edu.retencion.records;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Foto del registro académico de un estudiante en un periodo, sin datos personales ni texto libre:
 * solo métricas. Es la entrada del motor de riesgo (Fase 4).
 *
 * @param studentId            estudiante
 * @param termId               periodo de la foto ({@code null} si el estudiante no tiene inscripciones)
 * @param subjects             una entrada por inscripción del periodo
 * @param semesterAverage      promedio de los promedios por materia ({@code null} sin notas)
 * @param subjectsBelowPassing materias con promedio menor a 3.0
 * @param academicObservations observaciones ACADEMICA del periodo
 * @param conductObservations  observaciones de CONDUCTA del periodo
 * @param openCauses           causas de riesgo/retiro abiertas
 */
public record RecordsSnapshot(UUID studentId, UUID termId, List<SubjectSnapshot> subjects, BigDecimal semesterAverage,
                              int subjectsBelowPassing, int academicObservations, int conductObservations,
                              List<OpenCause> openCauses) {

    public RecordsSnapshot {
        subjects = List.copyOf(subjects);
        openCauses = List.copyOf(openCauses);
    }

    /**
     * Métricas de una materia (inscripción).
     *
     * @param enrollmentId      inscripción
     * @param groupId           grupo
     * @param subjectId         materia
     * @param subjectName       nombre de la materia (no es dato personal)
     * @param average           promedio simple ({@code null} sin notas)
     * @param gradeCount        notas registradas
     * @param belowPassing      promedio menor a 3.0
     * @param trend             SUBE, ESTABLE, BAJA o INSUFICIENTE
     * @param countedAbsences   faltas que cuentan para la pérdida
     * @param unjustified       faltas injustificadas, rechazadas o vencidas
     * @param pending           faltas con justificación pendiente
     * @param tardies           tardanzas
     * @param maxAbsences       límite de la política aplicable
     * @param warningThreshold  umbral de aviso de la política
     * @param criticalThreshold umbral crítico de la política
     * @param attendanceLevel   nivel de asistencia
     */
    public record SubjectSnapshot(UUID enrollmentId, UUID groupId, UUID subjectId, String subjectName,
                                  BigDecimal average, int gradeCount, boolean belowPassing, String trend,
                                  int countedAbsences, int unjustified, int pending, int tardies, int maxAbsences,
                                  int warningThreshold, int criticalThreshold, AttendanceLevel attendanceLevel) {
    }

    /**
     * Causa abierta del catálogo.
     *
     * @param code     código de la causa
     * @param category categoría (ACADEMICA, ECONOMICA, PERSONAL, SALUD, INSTITUCIONAL, OTRA)
     */
    public record OpenCause(String code, String category) {
    }
}
