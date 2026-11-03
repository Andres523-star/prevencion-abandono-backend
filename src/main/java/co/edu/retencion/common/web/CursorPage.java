package co.edu.retencion.common.web;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Página de resultados con paginación por keyset (cursor). Escala en tablas grandes porque
 * no usa OFFSET: el siguiente bloque se pide con {@code ?after=<nextCursor>}.
 *
 * @param items      elementos de la página
 * @param nextCursor cursor para la siguiente página, o {@code null} si no hay más
 */
public record CursorPage<T>(List<T> items, String nextCursor) {

    /** Tamaño máximo de página permitido en toda la API. */
    public static final int MAX_SIZE = 200;

    /**
     * Construye la página a partir de {@code size + 1} filas consultadas: si llegó la fila extra,
     * hay más resultados y el cursor es el id del último elemento visible.
     */
    public static <T> CursorPage<T> of(List<T> fetched, int size, Function<T, UUID> idOf) {
        if (fetched.size() <= size) {
            return new CursorPage<>(fetched, null);
        }
        List<T> page = fetched.subList(0, size);
        return new CursorPage<>(List.copyOf(page), idOf.apply(page.get(size - 1)).toString());
    }

    public static int clampSize(Integer requested) {
        if (requested == null || requested < 1) {
            return 50;
        }
        return Math.min(requested, MAX_SIZE);
    }
}
