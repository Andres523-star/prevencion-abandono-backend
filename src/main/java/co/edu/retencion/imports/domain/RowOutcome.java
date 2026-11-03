package co.edu.retencion.imports.domain;

/**
 * Resultado de una fila. El mensaje nunca contiene los valores de la fila (pueden ser datos
 * personales): solo el campo y el problema.
 *
 * @param result  resultado
 * @param message detalle (errores separados por "; ")
 */
public record RowOutcome(Result result, String message) {

    /** Resultado posible de una fila. */
    public enum Result {
        CREATED,
        UPDATED,
        UNCHANGED,
        /** Válida en modo dry-run (no se escribió). */
        VALID,
        ERROR
    }

    public static RowOutcome error(String message) {
        return new RowOutcome(Result.ERROR, message);
    }

    public static RowOutcome of(Result result) {
        return new RowOutcome(result, null);
    }
}
