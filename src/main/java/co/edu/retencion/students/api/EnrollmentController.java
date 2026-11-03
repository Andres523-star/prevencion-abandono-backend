package co.edu.retencion.students.api;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import co.edu.retencion.audit.Audited;
import co.edu.retencion.audit.SensitiveRead;
import co.edu.retencion.common.security.authorization.AccessAction;
import co.edu.retencion.common.security.authorization.RequireScope;
import co.edu.retencion.students.application.EnrollmentService;
import co.edu.retencion.students.application.StudentViews.EnrollmentView;
import co.edu.retencion.students.application.StudentViews.RosterEntry;

/** Inscripciones y lista de clase. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Inscripciones")
public class EnrollmentController {

    private final EnrollmentService enrollments;

    public EnrollmentController(EnrollmentService enrollments) {
        this.enrollments = enrollments;
    }

    public record EnrollRequest(@NotNull UUID groupId) {
    }

    @PostMapping("/students/{id}/enrollments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','COORDINADOR')")
    @RequireScope(resource = "GROUP", id = "#request.groupId()", action = AccessAction.WRITE)
    @Audited(action = "ENROLLMENT_CREATE", entity = "Enrollment", entityId = "#result.id")
    @Operation(summary = "Inscribe al estudiante en un grupo (respeta el cupo; idempotente)")
    public EnrollmentView enroll(@PathVariable UUID id, @Valid @RequestBody EnrollRequest request) {
        return enrollments.enroll(id, request.groupId());
    }

    @PostMapping("/enrollments/{id}/withdraw")
    @PreAuthorize("hasAnyRole('ADMIN','COORDINADOR')")
    @RequireScope(resource = "ENROLLMENT", id = "#id", action = AccessAction.WRITE)
    @Audited(action = "ENROLLMENT_WITHDRAW", entity = "Enrollment", entityId = "#id")
    @Operation(summary = "Retira al estudiante de la materia (no borra la inscripción)")
    public EnrollmentView withdraw(@PathVariable UUID id) {
        return enrollments.withdraw(id);
    }

    @GetMapping("/students/{id}/enrollments")
    @RequireScope(resource = "STUDENT", id = "#id")
    @Operation(summary = "Inscripciones del estudiante (opcionalmente de un periodo)")
    public List<EnrollmentView> ofStudent(@PathVariable UUID id, @RequestParam(required = false) UUID termId) {
        return enrollments.ofStudent(id, termId);
    }

    @GetMapping("/groups/{id}/roster")
    @PreAuthorize("hasAnyRole('ADMIN','CONSEJERO','PSICOLOGO','COORDINADOR','DOCENTE')")
    @SensitiveRead(entity = "GroupRoster", entityId = "#id", requireReason = false)
    @RequireScope(resource = "GROUP", id = "#id")
    @Operation(summary = "Lista de clase con nombres (el docente solo de sus grupos; auditada)")
    public List<RosterEntry> roster(@PathVariable UUID id) {
        return enrollments.roster(id);
    }
}
