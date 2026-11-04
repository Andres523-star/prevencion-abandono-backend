package co.edu.retencion.wellbeing.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import co.edu.retencion.audit.Audited;
import co.edu.retencion.audit.SensitiveRead;
import co.edu.retencion.wellbeing.application.WellbeingService;
import co.edu.retencion.wellbeing.application.WellbeingService.AssessmentView;
import co.edu.retencion.wellbeing.application.WellbeingService.ReferralView;
import co.edu.retencion.wellbeing.domain.WellbeingAssessment;
import co.edu.retencion.wellbeing.domain.WellbeingReferral;

/**
 * Bienestar: solo PSICOLOGO. Las lecturas exigen motivo ({@code X-Access-Reason}) y quedan en la
 * auditoría; ni siquiera ADMIN lee el contenido.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Bienestar")
@PreAuthorize("hasRole('PSICOLOGO')")
public class WellbeingController {

    private final WellbeingService wellbeing;

    public WellbeingController(WellbeingService wellbeing) {
        this.wellbeing = wellbeing;
    }

    public record AssessmentRequest(@NotNull WellbeingAssessment.Type type, @NotBlank @Size(max = 4000) String summary,
                                    @Size(max = 8000) String findings, LocalDate assessedOn) {
        @Override
        public String toString() {
            return "AssessmentRequest[type=" + type + "]";
        }
    }

    public record ReferralRequest(UUID assessmentId, @NotNull WellbeingReferral.Service service,
                                  @NotBlank @Size(max = 2000) String reason) {
        @Override
        public String toString() {
            return "ReferralRequest[service=" + service + "]";
        }
    }

    public record StatusRequest(@NotNull WellbeingReferral.Status status) {
    }

    @PostMapping("/students/{id}/wellbeing/assessments")
    @ResponseStatus(HttpStatus.CREATED)
    @Audited(action = "WELLBEING_ASSESSMENT_CREATE", entity = "Student", entityId = "#id")
    @Operation(summary = "Registra una evaluación física o psicológica")
    public AssessmentView record(@PathVariable UUID id, @Valid @RequestBody AssessmentRequest request) {
        return wellbeing.record(id, request.type(), request.summary(), request.findings(), request.assessedOn());
    }

    @GetMapping("/students/{id}/wellbeing/assessments")
    @SensitiveRead(entity = "WellbeingAssessment", entityId = "#id")
    @Operation(summary = "Evaluaciones del estudiante (requiere X-Access-Reason)")
    public List<AssessmentView> assessments(@PathVariable UUID id) {
        return wellbeing.assessments(id);
    }

    @PostMapping("/students/{id}/wellbeing/referrals")
    @ResponseStatus(HttpStatus.CREATED)
    @Audited(action = "WELLBEING_REFERRAL_CREATE", entity = "Student", entityId = "#id")
    @Operation(summary = "Remite al estudiante a un servicio de bienestar")
    public ReferralView refer(@PathVariable UUID id, @Valid @RequestBody ReferralRequest request) {
        return wellbeing.refer(id, request.assessmentId(), request.service(), request.reason());
    }

    @GetMapping("/students/{id}/wellbeing/referrals")
    @SensitiveRead(entity = "WellbeingReferral", entityId = "#id")
    @Operation(summary = "Remisiones del estudiante (requiere X-Access-Reason)")
    public List<ReferralView> referrals(@PathVariable UUID id) {
        return wellbeing.referrals(id);
    }

    @PostMapping("/wellbeing/referrals/{id}/status")
    @Audited(action = "WELLBEING_REFERRAL_STATUS", entity = "WellbeingReferral", entityId = "#id")
    @Operation(summary = "Cambia el estado de una remisión (ABIERTA → ATENDIDA → CERRADA)")
    public ReferralView move(@PathVariable UUID id, @Valid @RequestBody StatusRequest request) {
        return wellbeing.moveReferral(id, request.status());
    }
}
