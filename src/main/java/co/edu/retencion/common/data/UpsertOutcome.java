package co.edu.retencion.common.data;

/**
 * Resultado de una operación idempotente por clave natural (importaciones, datos sintéticos).
 * Reimportar el mismo archivo debe dar {@link #UNCHANGED} en todas las filas.
 */
public enum UpsertOutcome {
    CREATED,
    UPDATED,
    UNCHANGED
}
