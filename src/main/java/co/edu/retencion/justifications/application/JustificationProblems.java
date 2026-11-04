package co.edu.retencion.justifications.application;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import co.edu.retencion.common.error.ApiProblem;
import co.edu.retencion.common.error.ProblemCatalogContributor;

/** Prototipos de error de justificaciones. */
@Component
class JustificationProblems implements ProblemCatalogContributor {

    static final String FILE_INFECTED = "file_infected";
    static final String EVIDENCE_NOT_AVAILABLE = "evidence_not_available";
    static final String EVIDENCE_SCAN_PENDING = "evidence_scan_pending";

    @Override
    public List<ApiProblem> problems() {
        return List.of(
                new ApiProblem(FILE_INFECTED, HttpStatus.UNPROCESSABLE_ENTITY,
                        "El archivo fue rechazado por el antivirus"),
                new ApiProblem(EVIDENCE_NOT_AVAILABLE, HttpStatus.CONFLICT,
                        "La evidencia aún no está disponible (revisión antivirus pendiente)"),
                new ApiProblem(EVIDENCE_SCAN_PENDING, HttpStatus.CONFLICT,
                        "Hay evidencias en revisión antivirus; espera a que terminen para decidir"));
    }
}
