package co.edu.retencion.analytics.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Supresión de celdas pequeñas (servicio de dominio puro). Publicar "3 retirados" en un grupo pequeño
 * puede identificar a esas personas, así que los conteos entre 1 y {@code min - 1} se ocultan. El cero
 * no se oculta: no señala a nadie.
 *
 * <p>Con la <b>supresión complementaria</b> de {@link #maskColumn} tampoco se puede deducir la celda
 * oculta restándola de un total: si en una columna quedaría una sola celda oculta, se oculta además la
 * menor de las visibles.
 */
public final class CellSuppressor {

    private CellSuppressor() {
    }

    /** Oculta la celda si es pequeña. */
    public static Long mask(long count, int min) {
        return count > 0 && count < min ? null : Long.valueOf(count);
    }

    /** Supresión primaria y complementaria de una columna que suma un total conocido. */
    public static List<Long> maskColumn(List<Long> counts, int min) {
        List<Long> masked = new ArrayList<>(counts.size());
        int hidden = 0;
        for (Long count : counts) {
            Long value = count == null ? null : mask(count, min);
            masked.add(value);
            if (value == null) {
                hidden++;
            }
        }
        if (hidden == 1) {
            int smallest = -1;
            for (int i = 0; i < masked.size(); i++) {
                Long value = masked.get(i);
                if (value != null && value > 0 && (smallest < 0 || value < masked.get(smallest))) {
                    smallest = i;
                }
            }
            if (smallest >= 0) {
                masked.set(smallest, null);
            }
        }
        return masked;
    }

    /** Porcentaje con dos decimales; {@code null} si falta un dato o el total es cero. */
    public static BigDecimal percentage(Long part, Long total) {
        if (part == null || total == null || total == 0) {
            return null;
        }
        return BigDecimal.valueOf(part * 100).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }
}
