package co.edu.retencion.reports.application;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import co.edu.retencion.common.error.ApiProblem;
import co.edu.retencion.common.error.ProblemCatalogContributor;

/** Prototipos de error de los reportes. */
@Component
class ReportProblems implements ProblemCatalogContributor {

    static final String REASON_REQUIRED = "report_reason_required";
    static final String NOT_READY = "report_not_ready";
    static final String EXPIRED = "report_expired";

    @Override
    public List<ApiProblem> problems() {
        return List.of(
                new ApiProblem(REASON_REQUIRED, HttpStatus.BAD_REQUEST,
                        "Un reporte con identidad exige indicar el motivo en la cabecera X-Access-Reason"),
                new ApiProblem(NOT_READY, HttpStatus.CONFLICT, "El reporte todavía no está listo"),
                new ApiProblem(EXPIRED, HttpStatus.GONE, "El reporte venció; solicítalo de nuevo"));
    }
}
