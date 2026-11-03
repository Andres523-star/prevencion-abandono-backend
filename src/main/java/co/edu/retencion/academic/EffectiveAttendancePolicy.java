package co.edu.retencion.academic;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Política de asistencia efectiva para un grupo, ya resuelta (materia &gt; práctica clínica del
 * programa &gt; programa &gt; valores por defecto).
 *
 * <p>Reglas fijas del dominio, no configurables: la falta se cuenta por sesión (no por horas) y una
 * falta justificada solo se descuenta con aprobación del docente.
 *
 * @param maxAbsences                     faltas injustificadas con las que se pierde la materia
 * @param warningThreshold                falta en la que se emite aviso
 * @param criticalThreshold               falta que marca nivel crítico
 * @param justificationWindowBusinessDays días hábiles para justificar
 * @param tardiesPerAbsence               cada cuántas tardanzas se cuenta una falta ({@code null} = no suman)
 * @param source                          de dónde salió la política
 */
public record EffectiveAttendancePolicy(int maxAbsences, int warningThreshold, int criticalThreshold,
                                        int justificationWindowBusinessDays, Integer tardiesPerAbsence,
                                        PolicySource source) {

    /** Una sesión ausente cuenta como una falta, sin importar su duración. */
    @JsonProperty("countsPerSession")
    public boolean countsPerSession() {
        return true;
    }

    /** Solo el docente de la materia puede aprobar una justificación. */
    @JsonProperty("justificationRequiresTeacherApproval")
    public boolean justificationRequiresTeacherApproval() {
        return true;
    }

    /** Origen de la política efectiva. */
    public enum PolicySource {
        SUBJECT,
        PROGRAM_CLINICAL,
        PROGRAM,
        DEFAULT
    }
}
