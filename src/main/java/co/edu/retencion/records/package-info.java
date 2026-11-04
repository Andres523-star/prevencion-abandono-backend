/**
 * Módulo <b>records</b> ("academic-records" en CLAUDE.md): asistencia por sesión (tabla
 * particionada por periodo), contador de faltas por inscripción, notas con indicadores de riesgo,
 * observaciones de docentes/tutores y catálogo de causas de riesgo/retiro.
 *
 * <p>API pública: {@link co.edu.retencion.records.AttendanceLedger} (consulta y cambio del estado
 * de justificación de una falta), {@link co.edu.retencion.records.GradeBook} (importación de notas)
 * y los eventos {@link co.edu.retencion.records.AbsenceRecorded},
 * {@link co.edu.retencion.records.AbsenceRetracted},
 * {@link co.edu.retencion.records.AttendanceThresholdReached} y
 * {@link co.edu.retencion.records.SubjectLostByAbsences}.
 *
 * <p>Reglas fijas: la falta se cuenta por sesión (no por horas) y una falta justificada solo se
 * descuenta con la aprobación del docente.
 *
 * <p>Asistido por IA (Claude, Anthropic). Ver BITACORA_IA.md.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Registro académico")
package co.edu.retencion.records;
