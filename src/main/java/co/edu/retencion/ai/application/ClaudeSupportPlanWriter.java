package co.edu.retencion.ai.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;

import co.edu.retencion.ai.Anonymizer.AnonymizedPayload;
import co.edu.retencion.ai.ModelTier;
import co.edu.retencion.ai.StudentTextPlaceholders;
import co.edu.retencion.ai.SupportPlanWriter;

/**
 * {@link SupportPlanWriter} con el modelo capaz (los planes se piden en umbrales críticos). Valida
 * recursos citados y marcadores del mensaje al estudiante.
 */
@Service
public class ClaudeSupportPlanWriter implements SupportPlanWriter {

    static final String PURPOSE = "SUPPORT_PLAN";
    static final String SCHEMA = "support-plan.v1";

    private final StructuredCompletion completion;

    public ClaudeSupportPlanWriter(StructuredCompletion completion) {
        this.completion = completion;
    }

    @Override
    public Outcome draft(AnonymizedPayload payload, Set<String> citableResources) {
        StructuredCompletion.Result result = completion.complete(PURPOSE, PromptCatalog.SUPPORT_PLAN, SCHEMA,
                ModelTier.DEFAULT, payload, node -> semantic(node, citableResources));
        return new Outcome(result.output().map(ClaudeSupportPlanWriter::toDraft), result.call());
    }

    static List<String> semantic(JsonNode node, Set<String> citable) {
        List<String> errors = new ArrayList<>();
        JsonNode actions = node.path("actions");
        for (int i = 0; i < actions.size(); i++) {
            JsonNode id = actions.get(i).path("resourceId");
            if (id.isTextual() && !citable.contains(id.asText())) {
                errors.add("$.actions[" + i + "].resourceId no está en el catálogo entregado");
            }
        }
        if (!StudentTextPlaceholders.onlyAllowed(node.path("studentMessage").asText())) {
            errors.add("$.studentMessage usa marcadores no permitidos");
        }
        return errors;
    }

    static Draft toDraft(JsonNode n) {
        List<Action> actions = new ArrayList<>();
        n.path("actions").forEach(a -> actions.add(new Action(a.path("type").asText(), a.path("title").asText(),
                a.path("description").asText(), a.path("priority").asText(), a.path("suggestedOwner").asText(),
                a.path("resourceId").isTextual() ? a.path("resourceId").asText() : null, a.path("dueInDays").asInt())));
        return new Draft(actions, n.path("studentMessage").asText());
    }
}
