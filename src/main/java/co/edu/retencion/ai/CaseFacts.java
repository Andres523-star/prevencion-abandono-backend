package co.edu.retencion.ai;

import java.math.BigDecimal;
import java.util.List;

/**
 * Hechos seudonimizados de un caso, tal como los arma el motor de riesgo. No hay campo para nombre,
 * documento, correo, teléfono ni estrato: el tipo mismo impide pasarlos. El {@link Anonymizer}
 * decide además qué partes salen (lista blanca) y redacta el texto libre.
 *
 * @param anonCode             código seudónimo del estudiante
 * @param programCode          código del programa (ENF, MED, ...)
 * @param semester             semestre actual
 * @param semesterAverage      promedio del semestre ({@code null} sin notas)
 * @param subjectsBelowPassing materias con promedio menor a 3.0
 * @param subjects             métricas por materia
 * @param academicObservations observaciones académicas del periodo
 * @param conductObservations  observaciones de conducta del periodo
 * @param openCauses           causas de riesgo/retiro abiertas
 * @param context              contexto socioeconómico sin estrato ({@code null} si no hay perfil)
 * @param wellbeing            señales de bienestar (solo conteos)
 * @param score                puntaje del motor (reglas o ML) con sus factores
 * @param freeTexts            texto libre sin redactar (observaciones); el anonimizador decide si sale
 */
public record CaseFacts(String anonCode, String programCode, int semester, BigDecimal semesterAverage,
                        int subjectsBelowPassing, List<SubjectFacts> subjects, int academicObservations,
                        int conductObservations, List<CauseFacts> openCauses, ContextFacts context,
                        WellbeingFacts wellbeing, ScoreFacts score, List<String> freeTexts) {

    public CaseFacts {
        subjects = List.copyOf(subjects);
        openCauses = List.copyOf(openCauses);
        freeTexts = freeTexts == null ? List.of() : List.copyOf(freeTexts);
    }

    @Override
    public String toString() {
        return "CaseFacts[" + anonCode + "]";
    }

    /**
     * @param ref                   referencia corta de la materia en el payload (M1, M2...)
     * @param subjectName           nombre de la materia
     * @param average               promedio ({@code null} sin notas)
     * @param gradeCount            notas registradas
     * @param trend                 tendencia de notas
     * @param countedAbsences       faltas que cuentan
     * @param maxAbsences           límite de la política
     * @param attendanceLevel       NINGUNO, AVISO, CRITICO o PERDIDA
     * @param tardies               tardanzas
     * @param pendingJustifications faltas con justificación pendiente
     */
    public record SubjectFacts(String ref, String subjectName, BigDecimal average, int gradeCount, String trend,
                               int countedAbsences, int maxAbsences, String attendanceLevel, int tardies,
                               int pendingJustifications) {
    }

    /**
     * @param code     código del catálogo de causas
     * @param category categoría (SALUD se trata como señal sensible)
     */
    public record CauseFacts(String code, String category) {
    }

    /**
     * Contexto socioeconómico SIN estrato: sirve para recomendar recursos (transporte, horario),
     * nunca para castigar.
     *
     * @param works      trabaja
     * @param dependents personas a cargo
     * @param transport  medio de transporte
     */
    public record ContextFacts(Boolean works, Integer dependents, String transport) {
    }

    /**
     * @param openReferrals remisiones abiertas
     * @param assessments   evaluaciones registradas
     */
    public record WellbeingFacts(long openReferrals, long assessments) {
    }

    /**
     * @param score     puntaje 0-100
     * @param scoreType RULES o ML_CALIBRATED
     * @param level     BAJO, MEDIO o ALTO
     * @param factors   factores con su aporte
     */
    public record ScoreFacts(BigDecimal score, String scoreType, String level, List<FactorFacts> factors) {

        public ScoreFacts {
            factors = List.copyOf(factors);
        }
    }

    /**
     * @param code        código de la regla
     * @param category    ACADEMICO, ASISTENCIA, CONDUCTA, BIENESTAR, SOCIOECONOMICO o CAUSAS
     * @param points      aporte al puntaje
     * @param explanation explicación generada por plantilla (sin datos personales)
     */
    public record FactorFacts(String code, String category, BigDecimal points, String explanation) {
    }
}
