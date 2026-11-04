package co.edu.retencion.risk.domain;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Aporte de una regla al puntaje, con su explicación legible y su motivo estructurado (para que la
 * campanita de la Fase 5 genere su texto por plantilla).
 *
 * @param code        código de la regla (LOW_AVERAGE, ABSENCES...)
 * @param category    categoría (define quién puede verlo)
 * @param points      puntos aportados
 * @param explanation explicación generada por plantilla, sin datos personales
 * @param details     motivo estructurado (materia, promedio, faltas...)
 */
public record Factor(String code, FactorCategory category, BigDecimal points, String explanation,
                     Map<String, Object> details) {

    public Factor {
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    /** Categoría de un factor. */
    public enum FactorCategory {
        ACADEMICO,
        ASISTENCIA,
        CONDUCTA,
        /** Bienestar y salud: dato sensible; solo lo ve PSICOLOGO. */
        BIENESTAR,
        /** Contexto socioeconómico: solo ADMIN, CONSEJERO y PSICOLOGO; nunca el docente ni el coordinador. */
        SOCIOECONOMICO,
        CAUSAS
    }
}
