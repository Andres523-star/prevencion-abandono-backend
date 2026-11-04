package co.edu.retencion.ai.application;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import co.edu.retencion.common.error.ApiProblem;
import co.edu.retencion.common.error.ProblemCatalogContributor;

/** Prototipos de error del módulo de IA. */
@Component
class AiProblems implements ProblemCatalogContributor {

    static final String AGENT_DISABLED = "ai_agent_disabled";

    @Override
    public List<ApiProblem> problems() {
        return List.of(new ApiProblem(AGENT_DISABLED, HttpStatus.CONFLICT,
                "El agente de retención está desactivado (parámetro ai.agent.enabled)"));
    }
}
