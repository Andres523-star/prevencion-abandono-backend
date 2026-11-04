package co.edu.retencion.ai;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Capa de explicación del diseño híbrido: Claude explica los factores y propone recomendaciones
 * que citan recursos reales del catálogo. Nunca decide: el nivel final lo fija el motor (reglas o
 * ML) y toda acción la aprueba un humano.
 */
public interface RiskExplainer {

    /**
     * Nunca lanza por fallos de la IA: devuelve {@link Outcome} sin explicación y el motivo.
     *
     * @param payload           payload construido por el {@link Anonymizer} (se vuelve a validar antes de enviarlo)
     * @param citableResources  ids del catálogo que la IA puede citar (los que van en el payload)
     * @param tier              modelo económico o capaz
     */
    Outcome explain(Anonymizer.AnonymizedPayload payload, Set<String> citableResources, ModelTier tier);

    /**
     * @param explanation salida validada, vacía si hubo fallo
     * @param call        trazabilidad (modelo, versiones, hash, tokens, motivo de fallo)
     */
    record Outcome(Optional<Explanation> explanation, AiCallInfo call) {
    }

    /**
     * Salida de la IA validada contra JSON Schema.
     *
     * @param level           nivel sugerido por la IA (ALTO, MEDIO, BAJO)
     * @param summary         explicación breve
     * @param factors         factores detectados con peso 0-1
     * @param probableCauses  causas probables por categoría
     * @param recommendations recomendaciones concretas
     * @param confidence      confianza 0-1
     */
    record Explanation(String level, String summary, List<Factor> factors, List<ProbableCause> probableCauses,
                       List<Recommendation> recommendations, BigDecimal confidence) {
    }

    record Factor(String code, BigDecimal weight, String explanation) {
    }

    record ProbableCause(String category, String rationale) {
    }

    /**
     * @param resourceId      id del catálogo, o {@code null} si la acción no usa un recurso
     * @param action          acción concreta
     * @param priority        ALTA, MEDIA o BAJA
     * @param responsibleRole rol sugerido
     */
    record Recommendation(String resourceId, String action, String priority, String responsibleRole) {
    }
}
