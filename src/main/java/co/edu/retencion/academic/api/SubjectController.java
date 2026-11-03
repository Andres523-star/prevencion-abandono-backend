package co.edu.retencion.academic.api;

import java.util.Set;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import co.edu.retencion.academic.application.AcademicViews.SubjectView;
import co.edu.retencion.academic.application.SubjectService;
import co.edu.retencion.audit.Audited;
import co.edu.retencion.common.security.authorization.AccessAction;
import co.edu.retencion.common.security.authorization.RequireScope;

/** Materias. Las administra ADMIN o el coordinador del programa. */
@RestController
@RequestMapping("/api/v1/subjects")
@Tag(name = "Estructura académica")
public class SubjectController {

    private final SubjectService subjects;

    public SubjectController(SubjectService subjects) {
        this.subjects = subjects;
    }

    public record CreateSubjectRequest(@NotNull UUID programId,
                                       @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{2,30}$") String code,
                                       @NotBlank @Size(max = 200) String name,
                                       @Min(0) @Max(30) int credits, @Min(1) @Max(14) int suggestedSemester,
                                       @NotNull @Size(max = 20) Set<UUID> prerequisiteIds) {
    }

    public record UpdateSubjectRequest(@NotBlank @Size(max = 200) String name, @Min(0) @Max(30) int credits,
                                       @Min(1) @Max(14) int suggestedSemester, boolean active,
                                       @NotNull @Size(max = 20) Set<UUID> prerequisiteIds) {
    }

    @GetMapping("/{id}")
    @Operation(summary = "Obtiene una materia")
    public SubjectView get(@PathVariable UUID id) {
        return subjects.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','COORDINADOR')")
    @RequireScope(resource = "PROGRAM", id = "#request.programId()", action = AccessAction.WRITE)
    @Audited(action = "SUBJECT_CREATE", entity = "Subject", entityId = "#result.id")
    @Operation(summary = "Crea una materia con sus prerrequisitos")
    public SubjectView create(@Valid @RequestBody CreateSubjectRequest request) {
        return subjects.create(request.programId(), request.code(), request.name(), request.credits(),
                request.suggestedSemester(), request.prerequisiteIds());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','COORDINADOR')")
    @RequireScope(resource = "SUBJECT", id = "#id", action = AccessAction.WRITE)
    @Audited(action = "SUBJECT_UPDATE", entity = "Subject", entityId = "#id")
    @Operation(summary = "Edita una materia y sus prerrequisitos")
    public SubjectView update(@PathVariable UUID id, @Valid @RequestBody UpdateSubjectRequest request) {
        return subjects.update(id, request.name(), request.credits(), request.suggestedSemester(), request.active(),
                request.prerequisiteIds());
    }
}
