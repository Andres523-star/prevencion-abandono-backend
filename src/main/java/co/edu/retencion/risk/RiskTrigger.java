package co.edu.retencion.risk;

/** Origen de un análisis. */
public enum RiskTrigger {
    /** {@code POST /students/{id}/analyze}. */
    MANUAL,
    /** Cambio de notas o asistencia (con antirrebote). */
    EVENT,
    /** Umbral de asistencia alcanzado. */
    THRESHOLD,
    /** Lote nocturno. */
    BATCH,
    /** Reanálisis al resolver una intervención (mide su efecto: antes/después). */
    INTERVENTION
}
