package co.edu.retencion.students.api;

import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import co.edu.retencion.audit.Audited;
import co.edu.retencion.audit.SensitiveRead;
import co.edu.retencion.common.security.authorization.AccessAction;
import co.edu.retencion.common.security.authorization.RequireScope;
import co.edu.retencion.common.web.CursorPage;
import co.edu.retencion.students.DocumentType;
import co.edu.retencion.students.StudentRegistry.StudentRecord;
import co.edu.retencion.students.StudentStatus;
import co.edu.retencion.students.TransportMode;
import co.edu.retencion.students.application.StudentService;
import co.edu.retencion.students.application.StudentViews.SocioeconomicView;
import co.edu.retencion.students.application.StudentViews.StudentIdentityView;
import co.edu.retencion.students.application.StudentViews.StudentSelfView;
import co.edu.retencion.students.application.StudentViews.StudentView;

/**
 * Estudiantes. Los endpoints que devuelven identidad o perfil socioeconómico exigen rol, alcance y
 * motivo ({@code X-Access-Reason}), y quedan auditados. El resto devuelve solo datos seudonimizados.
 */
@RestController
@RequestMapping("/api/v1/students")
@Tag(name = "Estudiantes")
public class StudentController {

    private static final String STAFF_WITH_IDENTITY = "hasAnyRole('ADMIN','CONSEJERO','PSICOLOGO','COORDINADOR')";

    private final StudentService students;

    public StudentController(StudentService students) {
        this.students = students;
    }

    public record IdentityRequest(@NotBlank @Size(max = 200) String fullName, @NotNull DocumentType documentType,
                                  @NotBlank @Pattern(regexp = "^[A-Za-z0-9.\\- ]{4,20}$") String documentNumber,
                                  @Email @Size(max = 254) String email, @Size(max = 30) String phone) {

        @Override
        public String toString() {
            return "IdentityRequest[***]";
        }
    }

    public record SocioeconomicRequest(@Min(1) @Max(6) Integer stratum, Boolean works,
                                       @Min(0) @Max(20) Integer dependents, TransportMode transportMode) {
    }

    public record CreateStudentRequest(@NotNull @Valid IdentityRequest identity, @NotNull UUID programId,
                                       @Min(1) @Max(14) int currentSemester, @NotNull StudentStatus status,
                                       @NotNull LocalDate admissionDate, @Valid SocioeconomicRequest socioeconomic,
                                       @Size(max = 20) String consentVersion) {

        StudentRecord toRecord() {
            SocioeconomicRequest s = socioeconomic == null ? new SocioeconomicRequest(null, null, null, null) : socioeconomic;
            return new StudentRecord(identity.documentType(), identity.documentNumber(), identity.fullName(),
                    identity.email(), identity.phone(), programId, currentSemester, status, admissionDate,
                    s.stratum(), s.works(), s.dependents(), s.transportMode(), consentVersion);
        }
    }

    public record AcademicRequest(@NotNull UUID programId, @Min(1) @Max(14) int currentSemester,
                                  @NotNull StudentStatus status, @NotNull LocalDate admissionDate) {
    }

    public record LinkUserRequest(@NotNull UUID userId) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','COORDINADOR')")
    @RequireScope(resource = "PROGRAM", id = "#request.programId()", action = AccessAction.WRITE)
    @Audited(action = "STUDENT_CREATE", entity = "Student", entityId = "#result.id")
    @Operation(summary = "Registra un estudiante (genera su anon_code; la identidad se guarda cifrada)")
    public StudentView create(@Valid @RequestBody CreateStudentRequest request) {
        return students.create(request.toRecord());
    }

    @GetMapping
    @PreAuthorize(STAFF_WITH_IDENTITY)
    @Operation(summary = "Lista estudiantes seudonimizados (filtrado por el alcance del usuario)")
    public CursorPage<StudentView> list(@RequestParam(required = false) UUID programId,
                                        @RequestParam(required = false) StudentStatus status,
                                        @RequestParam(required = false) UUID after,
                                        @RequestParam(required = false) Integer size) {
        return students.list(programId, status, after, CursorPage.clampSize(size));
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('ESTUDIANTE')")
    @Operation(summary = "Portal: mi registro e identidad")
    public StudentSelfView me() {
        return students.me();
    }

    @GetMapping("/search")
    @PreAuthorize(STAFF_WITH_IDENTITY)
    @SensitiveRead(entity = "StudentDocumentSearch")
    @Operation(summary = "Busca por número de documento (blind index; requiere X-Access-Reason)")
    public StudentView search(@RequestParam @Pattern(regexp = "^[A-Za-z0-9.\\- ]{4,20}$") String document) {
        return students.findByDocument(document);
    }

    @GetMapping("/{id}")
    @RequireScope(resource = "STUDENT", id = "#id")
    @Operation(summary = "Estudiante seudonimizado (docente: solo si está en sus grupos; estudiante: solo él)")
    public StudentView get(@PathVariable UUID id) {
        return students.get(id);
    }

    @GetMapping("/{id}/identity")
    @PreAuthorize(STAFF_WITH_IDENTITY)
    @SensitiveRead(entity = "StudentIdentity", entityId = "#id")
    @RequireScope(resource = "STUDENT", id = "#id")
    @Operation(summary = "Identidad del estudiante (auditada; requiere X-Access-Reason)")
    public StudentIdentityView identity(@PathVariable UUID id) {
        return students.identity(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','COORDINADOR')")
    @RequireScope(resource = "STUDENT", id = "#id", action = AccessAction.WRITE)
    @Audited(action = "STUDENT_ACADEMIC_UPDATE", entity = "Student", entityId = "#id")
    @Operation(summary = "Actualiza programa, semestre, estado o fecha de ingreso")
    public StudentView updateAcademic(@PathVariable UUID id, @Valid @RequestBody AcademicRequest request) {
        return students.updateAcademic(id, request.programId(), request.currentSemester(), request.status(),
                request.admissionDate());
    }

    @PutMapping("/{id}/identity")
    @PreAuthorize("hasAnyRole('ADMIN','COORDINADOR')")
    @RequireScope(resource = "STUDENT", id = "#id", action = AccessAction.WRITE)
    @Audited(action = "STUDENT_IDENTITY_UPDATE", entity = "StudentIdentity", entityId = "#id")
    @Operation(summary = "Rectifica la identidad (derecho de rectificación)")
    public StudentIdentityView updateIdentity(@PathVariable UUID id, @Valid @RequestBody IdentityRequest request) {
        return students.updateIdentity(id, new StudentRecord(request.documentType(), request.documentNumber(),
                request.fullName(), request.email(), request.phone(), null, 1, null, null, null, null, null, null, null));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "STUDENT_DELETE", entity = "Student", entityId = "#id")
    @Operation(summary = "Borrado lógico (la supresión definitiva es un proceso formal)")
    public void delete(@PathVariable UUID id) {
        students.delete(id);
    }

    @PutMapping("/{id}/user")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "STUDENT_USER_LINK", entity = "Student", entityId = "#id")
    @Operation(summary = "Vincula manualmente la cuenta de usuario del estudiante")
    public StudentView linkUser(@PathVariable UUID id, @Valid @RequestBody LinkUserRequest request) {
        return students.linkUser(id, request.userId());
    }

    @GetMapping("/{id}/socioeconomic")
    @PreAuthorize("hasAnyRole('ADMIN','CONSEJERO','PSICOLOGO')")
    @SensitiveRead(entity = "SocioeconomicProfile", entityId = "#id")
    @RequireScope(resource = "STUDENT", id = "#id")
    @Operation(summary = "Perfil socioeconómico (solo ADMIN, CONSEJERO y PSICOLOGO; auditado)")
    public SocioeconomicView socioeconomic(@PathVariable UUID id) {
        return students.socioeconomic(id);
    }

    @PutMapping("/{id}/socioeconomic")
    @PreAuthorize("hasAnyRole('ADMIN','CONSEJERO','PSICOLOGO')")
    @RequireScope(resource = "STUDENT", id = "#id", action = AccessAction.WRITE)
    @Audited(action = "SOCIOECONOMIC_UPDATE", entity = "SocioeconomicProfile", entityId = "#id")
    @Operation(summary = "Actualiza el perfil socioeconómico")
    public SocioeconomicView updateSocioeconomic(@PathVariable UUID id, @Valid @RequestBody SocioeconomicRequest request) {
        return students.updateSocioeconomic(id, request.stratum(), request.works(), request.dependents(),
                request.transportMode());
    }
}
