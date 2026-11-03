package co.edu.retencion.academic.domain;

import co.edu.retencion.common.error.DomainException;
/**
 * Valores configurables de una política de asistencia, con sus invariantes.
 *
 * @param maxAbsences       faltas injustificadas con las que se pierde la materia (por defecto 4)
 * @param warningThreshold  falta que dispara el aviso (por defecto 2)
 * @param criticalThreshold falta que marca nivel crítico (por defecto 3)
 * @param justificationWindowBusinessDays días hábiles para justificar (por defecto 3)
 * @param tardiesPerAbsence cada cuántas tardanzas se cuenta una falta; {@code null} = no suman
 */
public record PolicyValues(int maxAbsences, int warningThreshold, int criticalThreshold,
                           int justificationWindowBusinessDays, Integer tardiesPerAbsence) {

    /** Valores por defecto del CLAUDE.md, usados cuando no hay ninguna política configurada. */
    public static final PolicyValues DEFAULT = new PolicyValues(4, 2, 3, 3, null);

    public PolicyValues {
        if (warningThreshold < 1 || warningThreshold >= criticalThreshold || criticalThreshold >= maxAbsences
                || maxAbsences > 50) {
            throw DomainException.badRequest(
                    "Umbrales inválidos: se requiere 1 <= aviso < crítico < máximo de faltas <= 50");
        }
        if (justificationWindowBusinessDays < 1 || justificationWindowBusinessDays > 30) {
            throw DomainException.badRequest("La ventana de justificación debe estar entre 1 y 30 días hábiles");
        }
        if (tardiesPerAbsence != null && (tardiesPerAbsence < 2 || tardiesPerAbsence > 10)) {
            throw DomainException.badRequest("Las tardanzas por falta deben estar entre 2 y 10 (o vacío)");
        }
    }
}
