package co.edu.retencion.records;

/**
 * Nivel de una inscripción según sus faltas contadas y la política de asistencia. El orden importa:
 * solo se emiten eventos cuando el nivel sube.
 */
public enum AttendanceLevel {
    NINGUNO,
    /** Se alcanzó el umbral de aviso (por defecto la 2.ª falta). */
    AVISO,
    /** Se alcanzó el umbral crítico (por defecto la 3.ª falta). */
    CRITICO,
    /** Se alcanzó el máximo de faltas: pérdida de la materia por inasistencia (por defecto la 4.ª). */
    PERDIDA;

    public static AttendanceLevel of(int countedAbsences, int warning, int critical, int max) {
        if (countedAbsences >= max) {
            return PERDIDA;
        }
        if (countedAbsences >= critical) {
            return CRITICO;
        }
        if (countedAbsences >= warning) {
            return AVISO;
        }
        return NINGUNO;
    }

    public boolean isAbove(AttendanceLevel other) {
        return ordinal() > other.ordinal();
    }
}
