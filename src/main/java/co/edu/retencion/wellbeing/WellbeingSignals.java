package co.edu.retencion.wellbeing;

import java.util.UUID;

/**
 * Señales de bienestar sin contenido (para el motor de riesgo de la Fase 4). Nunca expone el texto
 * de evaluaciones ni remisiones.
 */
public interface WellbeingSignals {

    Summary summary(UUID studentId);

    /**
     * @param psychologicalAssessments evaluaciones psicológicas registradas
     * @param physicalAssessments      evaluaciones físicas registradas
     * @param openReferrals            remisiones abiertas
     */
    record Summary(long psychologicalAssessments, long physicalAssessments, long openReferrals) {
    }
}
