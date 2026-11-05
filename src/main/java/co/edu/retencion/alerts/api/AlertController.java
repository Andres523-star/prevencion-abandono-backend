package co.edu.retencion.alerts.api;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import co.edu.retencion.alerts.application.AlertService;
import co.edu.retencion.alerts.application.AlertService.AlertView;
import co.edu.retencion.alerts.domain.Alert;
import co.edu.retencion.audit.Audited;
import co.edu.retencion.audit.SensitiveRead;
import co.edu.retencion.common.security.authorization.RequireScope;
import co.edu.retencion.common.web.CursorPage;

/** Alertas de riesgo para consejería, bienestar y coordinación. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Alertas")
@PreAuthorize("hasAnyRole('ADMIN','CONSEJERO','PSICOLOGO','COORDINADOR')")
public class AlertController {

    private final AlertService alerts;

    public AlertController(AlertService alerts) {
        this.alerts = alerts;
    }

    public record CloseRequest(@Size(max = 2000) String note) {
        @Override
        public String toString() {
            return "CloseRequest[***]";
        }
    }

    @GetMapping("/alerts")
    @SensitiveRead(entity = "Alert", requireReason = false)
    @Operation(summary = "Alertas visibles (asignadas a mí o de mis programas), keyset")
    public CursorPage<AlertView> list(@RequestParam(required = false) Alert.Status status,
                                      @RequestParam(defaultValue = "false") boolean mine,
                                      @RequestParam(required = false) UUID after,
                                      @RequestParam(required = false) Integer size) {
        return alerts.list(status, mine, after, size);
    }

    @GetMapping("/students/{id}/alerts")
    @RequireScope(resource = "STUDENT", id = "#id")
    @SensitiveRead(entity = "Alert", entityId = "#id", requireReason = false)
    @Operation(summary = "Alertas del estudiante")
    public List<AlertView> ofStudent(@PathVariable UUID id) {
        return alerts.ofStudent(id);
    }

    @PostMapping("/alerts/{id}/open")
    @Audited(action = "ALERT_OPEN", entity = "Alert", entityId = "#id")
    @Operation(summary = "Abre la alerta (si soy el responsable pasa a VISTA)")
    public AlertView open(@PathVariable UUID id) {
        return alerts.open(id);
    }

    @PostMapping("/alerts/{id}/attend")
    @Audited(action = "ALERT_ATTEND", entity = "Alert", entityId = "#id")
    @Operation(summary = "Marca la alerta como ATENDIDA")
    public AlertView attend(@PathVariable UUID id, @Valid @RequestBody(required = false) CloseRequest request) {
        return alerts.close(id, true, request == null ? null : request.note());
    }

    @PostMapping("/alerts/{id}/dismiss")
    @Audited(action = "ALERT_DISMISS", entity = "Alert", entityId = "#id")
    @Operation(summary = "Descarta la alerta (nota obligatoria)")
    public AlertView dismiss(@PathVariable UUID id, @Valid @RequestBody CloseRequest request) {
        return alerts.close(id, false, request.note());
    }

    @PostMapping("/alerts/escalations/run")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "ALERT_ESCALATION_RUN", entity = "Alert")
    @Operation(summary = "Ejecuta ahora el escalamiento de alertas sin atender")
    public int escalate() {
        return alerts.escalateOverdue();
    }
}
