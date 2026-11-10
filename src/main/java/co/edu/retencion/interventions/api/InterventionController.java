package co.edu.retencion.interventions.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
import co.edu.retencion.common.security.authorization.RequireScope;
import co.edu.retencion.interventions.application.InterventionService;
import co.edu.retencion.interventions.application.InterventionService.InterventionView;
import co.edu.retencion.interventions.application.InterventionService.NoteView;
import co.edu.retencion.interventions.application.InterventionService.PlanActionInput;
import co.edu.retencion.interventions.domain.Intervention;

/** Intervenciones de apoyo y conversión de planes aprobados. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Intervenciones")
public class InterventionController {

    static final String STAFF = "hasAnyRole('ADMIN','CONSEJERO','PSICOLOGO','COORDINADOR')";

    private final InterventionService interventions;

    public InterventionController(InterventionService interventions) {
        this.interventions = interventions;
    }

    public record CreateRequest(@NotNull Intervention.Type type, @NotBlank @Size(max = 200) String title,
                                @Size(max = 4000) String description, UUID responsibleUserId,
                                @Size(max = 12) String responsibleRole, LocalDate dueDate, UUID alertId,
                                UUID retentionCaseId) {
        @Override
        public String toString() {
            return "CreateRequest[" + type + "]";
        }
    }

    public record ResolveRequest(@NotBlank @Size(max = 4000) String result) {
        @Override
        public String toString() {
            return "ResolveRequest[***]";
        }
    }

    public record NoteRequest(@NotBlank @Size(max = 4000) String text, boolean restricted) {
        @Override
        public String toString() {
            return "NoteRequest[***]";
        }
    }

    public record ConvertRequest(@NotEmpty List<@Valid PlanActionInput> actions, @Size(max = 2000) String comment) {
    }

    @PostMapping("/students/{id}/interventions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(STAFF)
    @RequireScope(resource = "STUDENT", id = "#id")
    @Audited(action = "INTERVENTION_CREATE", entity = "Student", entityId = "#id")
    @Operation(summary = "Crea una intervención (guarda el riesgo actual como 'antes')")
    public InterventionView create(@PathVariable UUID id, @Valid @RequestBody CreateRequest r) {
        return interventions.create(new Intervention.Data(id, r.type(), r.title(), r.description(), r.responsibleUserId(),
                r.responsibleRole(), r.dueDate(), r.alertId(), null, r.retentionCaseId()));
    }

    @GetMapping("/students/{id}/interventions")
    @PreAuthorize(STAFF)
    @RequireScope(resource = "STUDENT", id = "#id")
    @Operation(summary = "Historial de intervenciones del estudiante")
    public List<InterventionView> ofStudent(@PathVariable UUID id) {
        return interventions.ofStudent(id);
    }

    @GetMapping("/interventions/mine")
    @PreAuthorize(STAFF)
    @Operation(summary = "Intervenciones de las que soy responsable")
    public List<InterventionView> mine() {
        return interventions.mine();
    }

    @PostMapping("/interventions/{id}/start")
    @Audited(action = "INTERVENTION_START", entity = "Intervention", entityId = "#id")
    @Operation(summary = "Pasa la intervención a EN_PROCESO")
    public InterventionView start(@PathVariable UUID id) {
        return interventions.start(id);
    }

    @PostMapping("/interventions/{id}/resolve")
    @Audited(action = "INTERVENTION_RESOLVE", entity = "Intervention", entityId = "#id")
    @Operation(summary = "Resuelve con resultado y reanaliza el riesgo (antes/después)")
    public InterventionView resolve(@PathVariable UUID id, @Valid @RequestBody ResolveRequest request) {
        return interventions.resolve(id, request.result());
    }

    @PostMapping("/interventions/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    @Audited(action = "INTERVENTION_NOTE", entity = "Intervention", entityId = "#id")
    @Operation(summary = "Nota de seguimiento (las de psicología son restringidas)")
    public NoteView addNote(@PathVariable UUID id, @Valid @RequestBody NoteRequest request) {
        return interventions.addNote(id, request.text(), request.restricted());
    }

    @GetMapping("/interventions/{id}/notes")
    @Operation(summary = "Notas visibles para mi rol")
    public List<NoteView> notes(@PathVariable UUID id) {
        return interventions.notes(id);
    }

    @PostMapping("/support-plans/{id}/convert")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('CONSEJERO','PSICOLOGO','COORDINADOR')")
    @Audited(action = "SUPPORT_PLAN_CONVERT", entity = "SupportPlan", entityId = "#id")
    @Operation(summary = "Aprueba el plan (con las acciones editadas) y lo convierte en intervenciones")
    public List<InterventionView> convert(@PathVariable UUID id, @Valid @RequestBody ConvertRequest request) {
        return interventions.convertPlan(id, request.actions(), request.comment());
    }
}
