package co.edu.retencion.records.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import co.edu.retencion.audit.Audited;
import co.edu.retencion.common.security.authorization.AccessAction;
import co.edu.retencion.common.security.authorization.RequireScope;
import co.edu.retencion.records.application.GradeService;
import co.edu.retencion.records.application.RecordsViews.GradeView;
import co.edu.retencion.records.application.RecordsViews.IndicatorsView;

/** Notas (escala 0.0–5.0, aprobación 3.0) e indicadores de riesgo. Sin cortes ni porcentajes. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Notas")
public class GradeController {

    private static final String WRITERS = "hasAnyRole('ADMIN','COORDINADOR','DOCENTE')";

    private final GradeService grades;

    public GradeController(GradeService grades) {
        this.grades = grades;
    }

    public record GradeRequest(@NotNull @DecimalMin("0.0") @DecimalMax("5.0") @Digits(integer = 1, fraction = 1) BigDecimal value,
                               @NotNull LocalDate assessedOn, @Size(max = 200) String description) {
    }

    public record GroupGradeRequest(@NotNull UUID enrollmentId,
                                    @NotNull @DecimalMin("0.0") @DecimalMax("5.0") @Digits(integer = 1, fraction = 1) BigDecimal value,
                                    @NotNull LocalDate assessedOn, @Size(max = 200) String description) {
    }

    public record BatchRequest(@NotNull @Size(min = 1, max = 1000) List<@Valid GroupGradeRequest> grades) {
    }

    @PostMapping("/groups/{id}/grades")
    @PreAuthorize(WRITERS)
    @RequireScope(resource = "GROUP", id = "#id", action = AccessAction.WRITE)
    @Audited(action = "GRADES_RECORD_BATCH", entity = "Group", entityId = "#id")
    @Operation(summary = "Registra notas por lote para el grupo (idempotente por inscripción, fecha y descripción)")
    public List<GradeView> recordForGroup(@PathVariable UUID id, @Valid @RequestBody BatchRequest request) {
        return grades.recordForGroup(id, request.grades().stream().map(g -> new GradeService.Entry(g.enrollmentId(),
                g.value(), g.assessedOn(), g.description())).toList());
    }

    @PostMapping("/enrollments/{id}/grades")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(WRITERS)
    @RequireScope(resource = "ENROLLMENT", id = "#id", action = AccessAction.WRITE)
    @Audited(action = "GRADE_RECORD", entity = "Enrollment", entityId = "#id")
    @Operation(summary = "Registra una nota individual")
    public GradeView recordForEnrollment(@PathVariable UUID id, @Valid @RequestBody GradeRequest request) {
        return grades.recordForEnrollment(id, new GradeService.Entry(id, request.value(), request.assessedOn(),
                request.description()));
    }

    @PutMapping("/grades/{id}")
    @PreAuthorize(WRITERS)
    @RequireScope(resource = "GRADE", id = "#id", action = AccessAction.WRITE)
    @Audited(action = "GRADE_CORRECT", entity = "Grade", entityId = "#id")
    @Operation(summary = "Corrige una nota")
    public GradeView correct(@PathVariable UUID id, @Valid @RequestBody GradeRequest request) {
        return grades.correct(id, request.value(), request.assessedOn(), request.description());
    }

    @DeleteMapping("/grades/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(WRITERS)
    @RequireScope(resource = "GRADE", id = "#id", action = AccessAction.WRITE)
    @Audited(action = "GRADE_DELETE", entity = "Grade", entityId = "#id")
    @Operation(summary = "Elimina una nota (borrado lógico)")
    public void delete(@PathVariable UUID id) {
        grades.delete(id);
    }

    @GetMapping("/groups/{id}/grades")
    @PreAuthorize("hasAnyRole('ADMIN','COORDINADOR','CONSEJERO','PSICOLOGO','DOCENTE')")
    @RequireScope(resource = "GROUP", id = "#id")
    @Operation(summary = "Notas del grupo")
    public List<GradeView> ofGroup(@PathVariable UUID id) {
        return grades.ofGroup(id);
    }

    @GetMapping("/students/{id}/grades")
    @RequireScope(resource = "STUDENT", id = "#id")
    @Operation(summary = "Notas del estudiante (el docente solo ve las de sus grupos)")
    public List<GradeView> ofStudent(@PathVariable UUID id, @RequestParam(required = false) UUID termId) {
        return grades.ofStudent(id, termId);
    }

    @GetMapping("/students/{id}/grade-indicators")
    @RequireScope(resource = "STUDENT", id = "#id")
    @Operation(summary = "Promedios por materia y semestre, materias bajo 3.0 y tendencia")
    public IndicatorsView indicators(@PathVariable UUID id, @RequestParam(required = false) UUID termId) {
        return grades.indicators(id, termId);
    }
}
