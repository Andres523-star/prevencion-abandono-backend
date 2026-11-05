package co.edu.retencion.risk;

import java.util.UUID;

/** Pide un análisis nuevo (p. ej. al resolver una intervención, para medir su efecto). */
public interface RiskAnalysis {

    /** Analiza ahora (origen {@link RiskTrigger#INTERVENTION}) y devuelve el resultado. */
    RiskQueries.LatestRisk reanalyze(UUID studentId, UUID requestedBy);
}
