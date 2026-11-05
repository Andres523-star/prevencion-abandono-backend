package co.edu.retencion.analytics;

import java.time.YearMonth;
import java.util.UUID;

import co.edu.retencion.common.data.UpsertOutcome;

/** Carga de series históricas de deserción (de periodos anteriores a la plataforma). */
public interface DropoutHistoryBook {

    /**
     * Crea o actualiza por clave natural (programa, mes, semestre).
     *
     * @param semester {@code null} si la serie no distingue semestre
     */
    UpsertOutcome upsert(UUID programId, YearMonth month, Integer semester, int enrolled, int dropouts, String label,
                         UUID actor);
}
