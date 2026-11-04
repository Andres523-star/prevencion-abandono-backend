package co.edu.retencion.risk.application;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import co.edu.retencion.common.error.ApiProblem;
import co.edu.retencion.common.error.ProblemCatalogContributor;

/** Prototipos de error del motor de riesgo. */
@Component
class RiskProblems implements ProblemCatalogContributor {

    static final String MODEL_REJECTED = "ml_model_rejected";
    static final String MODEL_NOT_APPROVABLE = "ml_model_not_approvable";

    @Override
    public List<ApiProblem> problems() {
        return List.of(
                new ApiProblem(MODEL_REJECTED, HttpStatus.UNPROCESSABLE_ENTITY,
                        "El modelo no cumple el contrato de variables, la política de sesgo o no se pudo cargar"),
                new ApiProblem(MODEL_NOT_APPROVABLE, HttpStatus.CONFLICT,
                        "El modelo es peor que el activo o tiene brechas de desempeño entre grupos; requiere anulación explícita con motivo"));
    }
}
