package co.edu.retencion.records.application;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import co.edu.retencion.common.error.ApiProblem;
import co.edu.retencion.common.error.ProblemCatalogContributor;

/** Prototipos de error del registro académico. */
@Component
class RecordsProblems implements ProblemCatalogContributor {

    static final String EDIT_WINDOW_CLOSED = "attendance_edit_window_closed";
    static final String CORRECTION_REASON_REQUIRED = "attendance_correction_reason_required";

    @Override
    public List<ApiProblem> problems() {
        return List.of(
                new ApiProblem(EDIT_WINDOW_CLOSED, HttpStatus.CONFLICT,
                        "La ventana de edición de la asistencia se cerró; solo coordinación o administración pueden corregirla"),
                new ApiProblem(CORRECTION_REASON_REQUIRED, HttpStatus.BAD_REQUEST,
                        "Corregir asistencia fuera de la ventana requiere el motivo (cabecera X-Access-Reason)"));
    }
}
