package co.edu.retencion.imports.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import co.edu.retencion.imports.domain.FieldSpec;
import co.edu.retencion.imports.domain.ImportJob;
import co.edu.retencion.imports.domain.RowOutcome;
import co.edu.retencion.imports.domain.RowValues;

/**
 * Estrategia de importación de un tipo de registro. Cada implementación define la plantilla
 * (campos), valida la fila (formato y referencias) y, fuera del modo dry-run, la aplica de forma
 * idempotente por clave natural.
 */
interface RowHandler {

    ImportJob.Type type();

    List<FieldSpec> fields();

    /** Procesa una fila; se ejecuta en su propia transacción. */
    RowOutcome process(RowValues row, Context context);

    /**
     * Contexto del trabajo.
     *
     * @param dryRun     solo validar
     * @param actor      usuario que lanzó la importación
     * @param seenKeys   claves naturales ya vistas en el archivo → fila donde aparecieron
     */
    record Context(boolean dryRun, UUID actor, Map<String, Integer> seenKeys) {

        static Context of(boolean dryRun, UUID actor) {
            return new Context(dryRun, actor, new HashMap<>());
        }

        /** Registra la clave; si ya estaba, agrega el error de duplicado y devuelve {@code false}. */
        boolean firstOccurrence(String key, RowValues row) {
            Integer previous = seenKeys.putIfAbsent(key, row.rowNumber());
            if (previous != null) {
                row.addError("registro repetido en el archivo (ver fila " + previous + ")");
                return false;
            }
            return true;
        }
    }
}
