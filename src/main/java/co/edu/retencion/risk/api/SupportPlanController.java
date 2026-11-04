package co.edu.retencion.risk.api;

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
import org.springframework.web.bind.annotation.RestController;

import co.edu.retencion.audit.Audited;
import co.edu.retencion.audit.SensitiveRead;
import co.edu.retencion.common.security.authorization.RequireScope;
import co.edu.retencion.risk.application.SupportPlanService;
import co.edu.retencion.risk.application.SupportPlanService.PlanView;

/**
 * Planes de apoyo por asistencia: propuestas que un humano aprueba o rechaza. La vista con el
 * nombre del estudiante rellenado es una lectura de dato personal (exige motivo y se audita).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Planes de apoyo")
public class SupportPlanController {

    private final SupportPlanService plans;

    public SupportPlanController(SupportPlanService plans) {
        this.plans = plans;
    }

    public record ReviewRequest(@Size(max = 2000) String comment) {
        @Override
        public String toString() {
            return "ReviewRequest[***]";
        }
    }

    @GetMapping("/students/{id}/support-plans")
    @PreAuthorize("hasAnyRole('ADMIN','CONSEJERO','PSICOLOGO','COORDINADOR')")
    @RequireScope(resource = "STUDENT", id = "#id")
    @Operation(summary = "Planes de apoyo del estudiante (textos con marcadores)")
    public List<PlanView> ofStudent(@PathVariable UUID id) {
        return plans.ofStudent(id);
    }

    @GetMapping("/support-plans/{id}/rendered")
    @PreAuthorize("hasAnyRole('ADMIN','CONSEJERO','PSICOLOGO','COORDINADOR')")
    @SensitiveRead(entity = "SupportPlanWithIdentity", entityId = "#id")
    @Operation(summary = "Plan con el nombre del estudiante rellenado (requiere X-Access-Reason)")
    public PlanView rendered(@PathVariable UUID id) {
        return plans.rendered(id);
    }

    @PostMapping("/support-plans/{id}/approve")
    @PreAuthorize("hasAnyRole('CONSEJERO','PSICOLOGO','COORDINADOR')")
    @Audited(action = "SUPPORT_PLAN_APPROVE", entity = "SupportPlan", entityId = "#id")
    @Operation(summary = "Aprueba el plan propuesto (revisión humana obligatoria)")
    public PlanView approve(@PathVariable UUID id, @Valid @RequestBody(required = false) ReviewRequest request) {
        return plans.approve(id, request == null ? null : request.comment());
    }

    @PostMapping("/support-plans/{id}/reject")
    @PreAuthorize("hasAnyRole('CONSEJERO','PSICOLOGO','COORDINADOR')")
    @Audited(action = "SUPPORT_PLAN_REJECT", entity = "SupportPlan", entityId = "#id")
    @Operation(summary = "Rechaza el plan propuesto (comentario obligatorio)")
    public PlanView reject(@PathVariable UUID id, @Valid @RequestBody ReviewRequest request) {
        return plans.reject(id, request.comment());
    }
}
