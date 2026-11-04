package co.edu.retencion.risk;

/** Quién produjo el nivel, los factores y las recomendaciones de un análisis. */
public enum AssessmentSource {
    /** Solo el motor (reglas o ML): la IA no se pidió o falló (fallback). */
    RULES,
    /** La IA subió el nivel del motor (solo si {@code risk.ai-can-escalate = true}). */
    AI,
    /** Nivel del motor + explicación y recomendaciones de la IA. */
    HYBRID
}
