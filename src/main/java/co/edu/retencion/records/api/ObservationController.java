package co.edu.retencion.records.api;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import co.edu.retencion.audit.Audited;
import co.edu.retencion.audit.SensitiveRead;
import co.edu.retencion.common.security.authorization.AccessAction;
import co.edu.retencion.common.security.authorization.RequireScope;
import co.edu.retencion.records.application.CauseService;
import co.edu.retencion.records.application.ObservationService;
import co.edu.retencion.records.application.RecordsViews.CauseView;
import co.edu.retencion.records.application.RecordsViews.ObservationView;
import co.edu.retencion.records.application.RecordsViews.StudentCauseView;
import co.edu.retencion.records.domain.DropoutCause;
import co.edu.retencion.records.domain.Observation;
import co.edu.retencion.records.domain.StudentCause;

/** Observaciones de docentes/tutores y causas de riesgo o retiro asociadas al estudiante. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Seguimiento")
public class ObservationController {

    private static final String FOLLOW_UP = "hasAnyRole('ADMIN','COORDINADOR','CONSEJERO','PSICOLOGO')";

    private final ObservationService observations;
    private final CauseService causes;

    public ObservationController(ObservationService observations, CauseService causes) {
        this.observations = observations;
        this.causes = causes;
    }

    public record ObservationRequest(UUID groupId, @NotNull Observation.Type type,
                                     @NotBlank @Size(max = 4000) String text) {
        @Override
        public String toString() {
            return "ObservationRequest[type=" + type + "]";
        }
    }

    public record CauseRequest(@NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String code,
                               @NotBlank @Size(max = 150) String name, @NotNull DropoutCause.Category category,
                               boolean active) {
    }

    public record StudentCauseRequest(@NotBlank @Pattern(regexp = "^[A-Z_]{2,40}$") String causeCode,
                                      @NotNull StudentCause.Source source, @Size(max = 2000) String notes) {
        @Override
        public String toString() {
            return "StudentCauseRequest[causeCode=" + causeCode + "]";
        }
    }

    @PostMapping("/students/{id}/observations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','COORDINADOR','CONSEJERO','DOCENTE')")
    @RequireScope(resource = "STUDENT", id = "#id", action = AccessAction.WRITE)
    @Audited(action = "OBSERVATION_CREATE", entity = "Student", entityId = "#id")
    @Operation(summary = "Registra una observación académica o de conducta")
    public ObservationView write(@PathVariable UUID id, @Valid @RequestBody ObservationRequest request) {
        return observations.write(id, request.groupId(), request.type(), request.text());
    }

    @GetMapping("/students/{id}/observations")
    @PreAuthorize("hasAnyRole('ADMIN','COORDINADOR','CONSEJERO','PSICOLOGO','DOCENTE')")
    @SensitiveRead(entity = "Observation", entityId = "#id", requireReason = false)
    @RequireScope(resource = "STUDENT", id = "#id")
    @Operation(summary = "Observaciones del estudiante (el docente solo ve las suyas)")
    public List<ObservationView> list(@PathVariable UUID id) {
        return observations.ofStudent(id);
    }

    @DeleteMapping("/observations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','COORDINADOR','CONSEJERO','DOCENTE')")
    @Audited(action = "OBSERVATION_DELETE", entity = "Observation", entityId = "#id")
    @Operation(summary = "Elimina (lógicamente) una observación propia")
    public void delete(@PathVariable UUID id) {
        observations.delete(id);
    }

    @GetMapping("/dropout-causes")
    @PreAuthorize(FOLLOW_UP)
    @Operation(summary = "Catálogo de causas de riesgo y retiro")
    public List<CauseView> catalog() {
        return causes.catalog();
    }

    @PutMapping("/dropout-causes")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "DROPOUT_CAUSE_UPSERT", entity = "DropoutCause", entityId = "#request.code()")
    @Operation(summary = "Crea o edita una causa del catálogo")
    public CauseView upsertCause(@Valid @RequestBody CauseRequest request) {
        return causes.upsertCause(request.code(), request.name(), request.category(), request.active());
    }

    @PostMapping("/students/{id}/causes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(FOLLOW_UP)
    @RequireScope(resource = "STUDENT", id = "#id", action = AccessAction.WRITE)
    @Audited(action = "STUDENT_CAUSE_OPEN", entity = "Student", entityId = "#id")
    @Operation(summary = "Asocia una causa de riesgo o retiro al estudiante")
    public StudentCauseView openCause(@PathVariable UUID id, @Valid @RequestBody StudentCauseRequest request) {
        return causes.open(id, request.causeCode(), request.source(), request.notes());
    }

    @GetMapping("/students/{id}/causes")
    @PreAuthorize(FOLLOW_UP)
    @SensitiveRead(entity = "StudentCause", entityId = "#id", requireReason = false)
    @RequireScope(resource = "STUDENT", id = "#id")
    @Operation(summary = "Causas del estudiante (las de salud solo para bienestar)")
    public List<StudentCauseView> listCauses(@PathVariable UUID id) {
        return causes.ofStudent(id);
    }

    @PostMapping("/student-causes/{id}/resolve")
    @PreAuthorize(FOLLOW_UP)
    @RequireScope(resource = "STUDENT_CAUSE", id = "#id", action = AccessAction.WRITE)
    @Audited(action = "STUDENT_CAUSE_RESOLVE", entity = "StudentCause", entityId = "#id")
    @Operation(summary = "Marca la causa como resuelta")
    public StudentCauseView resolveCause(@PathVariable UUID id) {
        return causes.resolve(id);
    }
}
