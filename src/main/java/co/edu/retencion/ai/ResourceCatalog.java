package co.edu.retencion.ai;

import java.util.List;
import java.util.Optional;

/**
 * Base de conocimiento de servicios de la universidad (RAG simple). La elegibilidad se evalúa en el
 * backend (con el estrato si hace falta); a la IA solo le llega la lista ya filtrada.
 */
public interface ResourceCatalog {

    /** Recursos activos para los que el estudiante es elegible, ordenados por id. */
    List<SupportResourceInfo> eligible(EligibilityProfile profile);

    Optional<SupportResourceInfo> find(String resourceId);

    /**
     * Datos para evaluar elegibilidad. Nunca salen hacia la IA.
     *
     * @param programCode programa
     * @param semester    semestre
     * @param stratum     estrato (puede ser {@code null})
     * @param works       trabaja (puede ser {@code null})
     * @param transport   medio de transporte (puede ser {@code null})
     */
    record EligibilityProfile(String programCode, int semester, Integer stratum, Boolean works, String transport) {

        @Override
        public String toString() {
            return "EligibilityProfile[" + programCode + "]";
        }
    }
}
