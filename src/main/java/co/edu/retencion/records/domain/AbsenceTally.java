package co.edu.retencion.records.domain;

import java.util.Collection;

import co.edu.retencion.academic.EffectiveAttendancePolicy;
import co.edu.retencion.records.AttendanceLevel;
import co.edu.retencion.records.AttendanceStatus;
import co.edu.retencion.records.JustificationState;

/**
 * Cálculo puro del contador de faltas a partir de los registros de asistencia y la política.
 *
 * <ul>
 *   <li>Cada sesión ausente pesa 1 (la falta se cuenta por sesión, no por horas).</li>
 *   <li>Justificada y aprobada: no cuenta. Pendiente: cuenta hasta que el docente decida.
 *       Rechazada, vencida o sin justificar: cuenta como injustificada.</li>
 *   <li>Tardanzas: si la política lo define, cada N tardanzas suman una falta.</li>
 * </ul>
 *
 * @param unjustified     injustificadas (sin justificar, rechazadas o vencidas)
 * @param pending         con justificación pendiente
 * @param justified       aprobadas
 * @param tardies         tardanzas
 * @param countedAbsences faltas que cuentan para los umbrales
 * @param level           nivel resultante
 */
public record AbsenceTally(int unjustified, int pending, int justified, int tardies, int countedAbsences,
                           AttendanceLevel level) {

    /** Lo mínimo de un registro que importa para el conteo. */
    public record Mark(AttendanceStatus status, JustificationState justification) {
    }

    public static AbsenceTally of(Collection<Mark> marks, EffectiveAttendancePolicy policy) {
        int unjustified = 0;
        int pending = 0;
        int justified = 0;
        int tardies = 0;
        for (Mark mark : marks) {
            if (mark.status() == AttendanceStatus.TARDANZA) {
                tardies++;
            } else if (mark.status() == AttendanceStatus.FALTA) {
                switch (mark.justification()) {
                    case APROBADA -> justified++;
                    case PENDIENTE -> pending++;
                    default -> unjustified++;
                }
            }
        }
        int fromTardies = policy.tardiesPerAbsence() == null ? 0 : tardies / policy.tardiesPerAbsence();
        int counted = unjustified + pending + fromTardies;
        AttendanceLevel level = AttendanceLevel.of(counted, policy.warningThreshold(), policy.criticalThreshold(),
                policy.maxAbsences());
        return new AbsenceTally(unjustified, pending, justified, tardies, counted, level);
    }
}
