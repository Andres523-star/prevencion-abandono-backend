package co.edu.retencion.academic.api;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import co.edu.retencion.academic.EffectiveAttendancePolicy;
import co.edu.retencion.academic.application.AcademicViews.PolicyView;
import co.edu.retencion.academic.application.AttendancePolicyService;
import co.edu.retencion.academic.domain.PolicyValues;
import co.edu.retencion.audit.Audited;
import co.edu.retencion.common.security.authorization.AccessAction;
import co.edu.retencion.common.security.authorization.RequireScope;

/**
 * Políticas de asistencia. Reglas fijas (no editables): la falta cuenta por sesión y solo el
 * docente aprueba justificaciones. Lo configurable: máximo de faltas, umbrales, ventana de
 * justificación y equivalencia de tardanzas.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Políticas de asistencia")
public class AttendancePolicyController {

    private final AttendancePolicyService policies;

    public AttendancePolicyController(AttendancePolicyService policies) {
        this.policies = policies;
    }

    public record PolicyRequest(@Min(2) @Max(50) int maxAbsences, @Min(1) @Max(49) int warningThreshold,
                                @Min(1) @Max(49) int criticalThreshold,
                                @Min(1) @Max(30) int justificationWindowBusinessDays,
                                @Min(2) @Max(10) Integer tardiesPerAbsence) {
        PolicyValues toValues() {
            return new PolicyValues(maxAbsences, warningThreshold, criticalThreshold, justificationWindowBusinessDays,
                    tardiesPerAbsence);
        }
    }

    @GetMapping("/groups/{id}/attendance-policy")
    @RequireScope(resource = "GROUP", id = "#id")
    @Operation(summary = "Política efectiva del grupo (materia > práctica clínica > programa > defecto)")
    public EffectiveAttendancePolicy effective(@PathVariable UUID id) {
        return policies.effectiveForGroup(id);
    }

    @GetMapping("/programs/{id}/attendance-policy")
    @Operation(summary = "Política del programa (clinical=true: la de sus prácticas clínicas)")
    public ResponseEntity<PolicyView> programPolicy(@PathVariable UUID id,
                                                    @RequestParam(defaultValue = "false") boolean clinical) {
        return ResponseEntity.of(policies.programPolicy(id, clinical));
    }

    @PutMapping("/programs/{id}/attendance-policy")
    @PreAuthorize("hasAnyRole('ADMIN','COORDINADOR')")
    @RequireScope(resource = "PROGRAM", id = "#id", action = AccessAction.WRITE)
    @Audited(action = "ATTENDANCE_POLICY_PROGRAM_PUT", entity = "Program", entityId = "#id")
    @Operation(summary = "Define la política del programa o de sus prácticas clínicas")
    public PolicyView putProgramPolicy(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean clinical,
                                       @Valid @RequestBody PolicyRequest request) {
        return policies.putProgramPolicy(id, clinical, request.toValues());
    }

    @GetMapping("/subjects/{id}/attendance-policy")
    @Operation(summary = "Política propia de la materia, si la tiene")
    public ResponseEntity<PolicyView> subjectPolicy(@PathVariable UUID id) {
        return ResponseEntity.of(policies.subjectPolicy(id));
    }

    @PutMapping("/subjects/{id}/attendance-policy")
    @PreAuthorize("hasAnyRole('ADMIN','COORDINADOR')")
    @RequireScope(resource = "SUBJECT", id = "#id", action = AccessAction.WRITE)
    @Audited(action = "ATTENDANCE_POLICY_SUBJECT_PUT", entity = "Subject", entityId = "#id")
    @Operation(summary = "Define una política propia para la materia (sobrescribe la del programa)")
    public PolicyView putSubjectPolicy(@PathVariable UUID id, @Valid @RequestBody PolicyRequest request) {
        return policies.putSubjectPolicy(id, request.toValues());
    }

    @DeleteMapping("/subjects/{id}/attendance-policy")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','COORDINADOR')")
    @RequireScope(resource = "SUBJECT", id = "#id", action = AccessAction.WRITE)
    @Audited(action = "ATTENDANCE_POLICY_SUBJECT_DELETE", entity = "Subject", entityId = "#id")
    @Operation(summary = "Elimina la política propia de la materia")
    public void deleteSubjectPolicy(@PathVariable UUID id) {
        policies.deleteSubjectPolicy(id);
    }
}
