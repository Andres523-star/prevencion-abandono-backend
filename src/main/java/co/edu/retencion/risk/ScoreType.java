package co.edu.retencion.risk;

/**
 * Qué significa el número {@code score} de un análisis.
 */
public enum ScoreType {
    /** Puntaje 0-100 sumado por reglas. NO es una probabilidad y no debe mostrarse como tal. */
    RULES,
    /** Probabilidad calibrada del modelo ML (0-1, también expresada 0-100). Única que se muestra como probabilidad. */
    ML_CALIBRATED
}
