package co.edu.retencion.academic.api;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import co.edu.retencion.academic.application.AcademicViews.ProgramView;
import co.edu.retencion.academic.application.AcademicViews.SubjectView;
import co.edu.retencion.academic.application.ProgramService;
import co.edu.retencion.academic.application.SubjectService;
import co.edu.retencion.audit.Audited;

/** Programas académicos (catálogo legible por cualquier usuario autenticado). */
@RestController
@RequestMapping("/api/v1/programs")
@Tag(name = "Estructura académica")
public class ProgramController {

    private final ProgramService programs;
    private final SubjectService subjects;

    public ProgramController(ProgramService programs, SubjectService subjects) {
        this.programs = programs;
        this.subjects = subjects;
    }

    public record UpdateProgramRequest(@NotBlank @Size(max = 120) String name, boolean clinical, boolean active) {
    }

    @GetMapping
    @Operation(summary = "Lista los programas")
    public List<ProgramView> list() {
        return programs.list();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Obtiene un programa")
    public ProgramView get(@PathVariable UUID id) {
        return programs.get(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "PROGRAM_UPDATE", entity = "Program", entityId = "#id")
    @Operation(summary = "Edita un programa (solo ADMIN)")
    public ProgramView update(@PathVariable UUID id, @Valid @RequestBody UpdateProgramRequest request) {
        return programs.update(id, request.name(), request.clinical(), request.active());
    }

    @GetMapping("/{id}/subjects")
    @Operation(summary = "Materias del programa (plan de estudios)")
    public List<SubjectView> subjects(@PathVariable UUID id) {
        return subjects.listByProgram(id);
    }
}
