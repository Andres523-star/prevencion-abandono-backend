package co.edu.retencion.records;

import java.util.List;
import java.util.UUID;

/**
 * API pública de lectura del registro académico para el motor de riesgo. Entrega métricas, no
 * registros: así el motor no conoce las tablas de este módulo.
 */
public interface StudentRecords {

    /**
     * Foto del estudiante en un periodo.
     *
     * @param termId periodo; {@code null} = periodo actual (el más reciente no cerrado con
     *               inscripciones del estudiante, o el más reciente si todos están cerrados)
     */
    RecordsSnapshot snapshot(UUID studentId, UUID termId);

    /**
     * Textos de las observaciones más recientes, descifrados. Solo para el redactor de PII del
     * módulo de IA cuando el envío de texto libre está activado por configuración; nunca deben
     * registrarse en logs.
     */
    List<String> recentObservationTexts(UUID studentId, int limit);
}
