package co.edu.retencion.records;

/**
 * Estado de justificación de una falta.
 * <ul>
 *   <li>NINGUNA: aún sin justificar (dentro o fuera del plazo).</li>
 *   <li>PENDIENTE: el estudiante envió una justificación; cuenta como falta hasta que el docente decida.</li>
 *   <li>APROBADA: el docente aprobó; la falta se descuenta del contador.</li>
 *   <li>RECHAZADA / VENCIDA: cuenta como falta injustificada y queda en vigilancia.</li>
 * </ul>
 */
public enum JustificationState {
    NINGUNA,
    PENDIENTE,
    APROBADA,
    RECHAZADA,
    VENCIDA
}
