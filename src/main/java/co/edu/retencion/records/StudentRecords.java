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

    /** Faltas que cuentan por semana (lunes) en los grupos indicados (gráfica de la vista del chat). */
    List<WeeklyAbsences> weeklyAbsences(UUID studentId, java.util.Set<UUID> groupIds);

    /** Notas registradas en los grupos indicados, en orden cronológico. */
    List<GradePoint> grades(UUID studentId, java.util.Set<UUID> groupIds);

    /**
     * @param groupId   grupo
     * @param weekStart lunes de la semana
     * @param absences  faltas (incluye pendientes de revisión)
     */
    record WeeklyAbsences(UUID groupId, java.time.LocalDate weekStart, int absences) {
    }

    /**
     * @param groupId    grupo
     * @param value      nota
     * @param assessedOn fecha
     */
    record GradePoint(UUID groupId, java.math.BigDecimal value, java.time.LocalDate assessedOn) {
    }
}
