package co.edu.retencion.portal.api;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.portal.application.PortalService;
import co.edu.retencion.portal.application.PortalService.AbsenceItem;
import co.edu.retencion.portal.application.PortalService.Overview;
import co.edu.retencion.portal.application.PortalService.Support;

/** Portal del estudiante: siempre sus propios datos, resueltos desde su usuario. */
@RestController
@RequestMapping("/api/v1/portal")
@PreAuthorize("hasRole('ESTUDIANTE')")
@Tag(name = "Portal del estudiante")
public class PortalController {

    private final PortalService portal;

    public PortalController(PortalService portal) {
        this.portal = portal;
    }

    @GetMapping("/overview")
    @Operation(summary = "Mis materias: faltas con la política que aplica, notas, docente y su enlace de cita")
    public Overview overview() {
        return portal.overview(CurrentUser.require());
    }

    @GetMapping("/absences")
    @Operation(summary = "Mi historial de faltas y tardanzas, con el estado de la justificación y su plazo")
    public List<AbsenceItem> absences() {
        return portal.absences(CurrentUser.require());
    }

    @GetMapping("/support")
    @Operation(summary = "Apoyo aprobado para mí y servicios de la universidad (sin etiquetas de riesgo)")
    public Support support() {
        return portal.support(CurrentUser.require());
    }
}
