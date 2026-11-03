package co.edu.retencion.audit.application;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import co.edu.retencion.common.error.ApiProblem;
import co.edu.retencion.common.error.ProblemCatalogContributor;

/** Prototipos de error propios del módulo de auditoría. */
@Component
class AuditProblems implements ProblemCatalogContributor {

    static final String REASON_REQUIRED = "access_reason_required";

    @Override
    public List<ApiProblem> problems() {
        return List.of(new ApiProblem(REASON_REQUIRED, HttpStatus.BAD_REQUEST,
                "Se requiere el motivo del acceso a datos sensibles"));
    }
}
