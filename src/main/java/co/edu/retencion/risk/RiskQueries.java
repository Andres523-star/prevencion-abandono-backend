package co.edu.retencion.risk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Consulta pública del último riesgo de un estudiante (para alertas y tableros de fases futuras). */
public interface RiskQueries {

    Optional<LatestRisk> latest(UUID studentId);

    /**
     * @param assessmentId análisis
     * @param level        nivel
     * @param score        puntaje
     * @param scoreType    tipo de puntaje
     * @param assessedAt   fecha
     */
    record LatestRisk(UUID assessmentId, RiskLevel level, BigDecimal score, ScoreType scoreType, Instant assessedAt) {
    }
}
