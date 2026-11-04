package co.edu.retencion.risk.domain;

import java.util.UUID;

import co.edu.retencion.records.AttendanceLevel;
import co.edu.retencion.records.RecordsSnapshot;
import co.edu.retencion.students.SocioeconomicInfo;
import co.edu.retencion.wellbeing.WellbeingSignals;

/**
 * Entrada de los scorers: todo lo que el motor sabe de un estudiante, sin datos de identidad.
 *
 * @param studentId     estudiante
 * @param anonCode      código seudónimo
 * @param programId     programa
 * @param programCode   código del programa
 * @param semester      semestre actual
 * @param records       foto académica del periodo
 * @param socioeconomic perfil socioeconómico ({@code null} si no hay); el estrato nunca suma puntaje
 * @param wellbeing     conteos de bienestar
 */
public record RiskFeatures(UUID studentId, String anonCode, UUID programId, String programCode, int semester,
                           RecordsSnapshot records, SocioeconomicInfo socioeconomic,
                           WellbeingSignals.Summary wellbeing) {

    @Override
    public String toString() {
        return "RiskFeatures[" + anonCode + "]";
    }

    /** Hay alguna señal académica o de asistencia (requisito para usar el contexto socioeconómico). */
    public boolean hasAcademicOrAttendanceSignal() {
        boolean lowAverage = records.semesterAverage() != null
                && records.semesterAverage().compareTo(new java.math.BigDecimal("3.0")) < 0;
        boolean absences = records.subjects().stream()
                .anyMatch(s -> s.attendanceLevel() != null && s.attendanceLevel() != AttendanceLevel.NINGUNO);
        return lowAverage || records.subjectsBelowPassing() > 0 || absences;
    }
}
