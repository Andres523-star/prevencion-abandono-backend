package co.edu.retencion.dashboard.api;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import co.edu.retencion.audit.SensitiveRead;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.common.web.CursorPage;
import co.edu.retencion.dashboard.application.DashboardService;
import co.edu.retencion.dashboard.application.DashboardService.StudentRow;
import co.edu.retencion.dashboard.application.DashboardService.Summary;
import co.edu.retencion.risk.RiskLevel;

/** Tablero. Las listas revelan el nivel de riesgo de personas: la lectura queda auditada. */
@RestController
@RequestMapping("/api/v1/dashboard")
@PreAuthorize("hasAnyRole('ADMIN','CONSEJERO','PSICOLOGO','COORDINADOR','DOCENTE')")
@Tag(name = "Tablero")
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/summary")
    @SensitiveRead(entity = "Dashboard", requireReason = false)
    @Operation(summary = "Totales, listas ALTO/MEDIO/BAJO y últimas notificaciones, según el rol")
    public Summary summary(@RequestParam(required = false) Integer size) {
        return dashboard.summary(CurrentUser.require(), size);
    }

    @GetMapping("/students")
    @SensitiveRead(entity = "Dashboard", requireReason = false)
    @Operation(summary = "Siguiente página de una lista por nivel (keyset)")
    public CursorPage<StudentRow> students(@RequestParam RiskLevel level, @RequestParam(required = false) UUID after,
                                           @RequestParam(required = false) Integer size) {
        return dashboard.students(CurrentUser.require(), level, after, size);
    }
}
