package co.edu.retencion.ai.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;

import co.edu.retencion.ai.Anonymizer.AnonymizedPayload;
import co.edu.retencion.ai.ModelTier;
import co.edu.retencion.ai.RiskExplainer;

/**
 * {@link RiskExplainer} con Claude. Además del esquema, exige que cada recomendación cite un
 * recurso que estaba en el payload (o ninguno): la IA no puede inventar servicios.
 */
@Service
public class ClaudeRiskExplainer implements RiskExplainer {

    static final String PURPOSE = "RISK_EXPLAIN";
    static final String SCHEMA = "risk-explanation.v1";

    private final StructuredCompletion completion;

    public ClaudeRiskExplainer(StructuredCompletion completion) {
        this.completion = completion;
    }

    @Override
    public Outcome explain(AnonymizedPayload payload, Set<String> citableResources, ModelTier tier) {
        StructuredCompletion.Result result = completion.complete(PURPOSE, PromptCatalog.RISK_EXPLAINER, SCHEMA, tier,
                payload, node -> citations(node.path("recommendations"), citableResources));
        return new Outcome(result.output().map(ClaudeRiskExplainer::toExplanation), result.call());
    }

    /** Errores por recursos citados que no estaban en la lista entregada a la IA. */
    static List<String> citations(JsonNode items, Set<String> citable) {
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            JsonNode id = items.get(i).path("resourceId");
            if (id.isTextual() && !citable.contains(id.asText())) {
                errors.add("$.recommendations[" + i + "].resourceId no está en el catálogo entregado");
            }
        }
        return errors;
    }

    private static Explanation toExplanation(JsonNode n) {
        List<Factor> factors = new ArrayList<>();
        n.path("factors").forEach(f -> factors.add(new Factor(f.path("code").asText(),
                f.path("weight").decimalValue(), f.path("explanation").asText())));
        List<ProbableCause> causes = new ArrayList<>();
        n.path("probableCauses").forEach(c -> causes.add(new ProbableCause(c.path("category").asText(),
                c.path("rationale").asText())));
        List<Recommendation> recommendations = new ArrayList<>();
        n.path("recommendations").forEach(r -> recommendations.add(new Recommendation(
                r.path("resourceId").isTextual() ? r.path("resourceId").asText() : null, r.path("action").asText(),
                r.path("priority").asText(), r.path("responsibleRole").asText())));
        BigDecimal confidence = n.path("confidence").decimalValue();
        return new Explanation(n.path("level").asText(), n.path("summary").asText(), factors, causes,
                recommendations, confidence);
    }
}
