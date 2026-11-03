package co.edu.retencion.academic.api;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
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

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import co.edu.retencion.academic.GroupType;
import co.edu.retencion.academic.application.AcademicViews.GenerationResult;
import co.edu.retencion.academic.application.AcademicViews.GroupView;
import co.edu.retencion.academic.application.AcademicViews.SessionView;
import co.edu.retencion.academic.application.GroupService;
import co.edu.retencion.academic.domain.ClassSession;
import co.edu.retencion.academic.domain.CourseGroup;
import co.edu.retencion.audit.Audited;
import co.edu.retencion.common.security.authorization.AccessAction;
import co.edu.retencion.common.security.authorization.RequireScope;
import co.edu.retencion.common.web.CursorPage;

/**
 * Grupos, horarios y sesiones de clase. Lectura según alcance (el docente solo sus grupos);
 * escritura por ADMIN o el coordinador del programa; el estado de la sesión lo cambia también
 * el docente del grupo.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Grupos y sesiones")
public class GroupController {

    private final GroupService groups;

    public GroupController(GroupService groups) {
        this.groups = groups;
    }

    public record SlotRequest(@NotNull DayOfWeek day, @NotNull LocalTime start, @NotNull LocalTime end,
                              @Size(max = 60) String room) {
        CourseGroup.Slot toSlot() {
            return new CourseGroup.Slot(day, start, end, room);
        }
    }

    public record CreateGroupRequest(@NotNull UUID subjectId, @NotNull UUID termId,
                                     @NotBlank @Pattern(regexp = "^[A-Za-z0-9-]{1,20}$") String code,
                                     UUID teacherUserId, @Min(1) @Max(1000) int capacity, @NotNull GroupType groupType,
                                     @NotNull @Size(max = 14) List<@Valid SlotRequest> schedule) {
    }

    public record AssignTeacherRequest(@NotNull UUID teacherUserId) {
    }

    public record ScheduleRequest(@NotNull @Size(max = 14) List<@Valid SlotRequest> schedule) {
    }

    public record SessionStatusRequest(@NotNull ClassSession.Status status, @Size(max = 300) String reason) {
    }

    @PostMapping("/groups")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','COORDINADOR')")
    @RequireScope(resource = "SUBJECT", id = "#request.subjectId()", action = AccessAction.WRITE)
    @Audited(action = "GROUP_CREATE", entity = "Group", entityId = "#result.id")
    @Operation(summary = "Crea un grupo (materia + periodo + docente + cupo) con su horario")
    public GroupView create(@Valid @RequestBody CreateGroupRequest request) {
        return groups.create(request.subjectId(), request.termId(), request.code(), request.teacherUserId(),
                request.capacity(), request.groupType(), request.schedule().stream().map(SlotRequest::toSlot).toList());
    }

    @GetMapping("/groups")
    @PreAuthorize("hasAnyRole('ADMIN','CONSEJERO','PSICOLOGO','COORDINADOR','DOCENTE')")
    @Operation(summary = "Lista grupos (el docente solo ve los suyos; el coordinador, los de su programa)")
    public CursorPage<GroupView> search(@RequestParam(required = false) UUID termId,
                                        @RequestParam(required = false) UUID subjectId,
                                        @RequestParam(required = false) UUID after,
                                        @RequestParam(required = false) Integer size) {
        return groups.search(termId, subjectId, after, CursorPage.clampSize(size));
    }

    @GetMapping("/groups/{id}")
    @RequireScope(resource = "GROUP", id = "#id")
    @Operation(summary = "Obtiene un grupo con su horario")
    public GroupView get(@PathVariable UUID id) {
        return groups.get(id);
    }

    @PutMapping("/groups/{id}/teacher")
    @PreAuthorize("hasAnyRole('ADMIN','COORDINADOR')")
    @RequireScope(resource = "GROUP", id = "#id", action = AccessAction.WRITE)
    @Audited(action = "GROUP_TEACHER_ASSIGN", entity = "Group", entityId = "#id")
    @Operation(summary = "Asigna o cambia el docente del grupo")
    public GroupView assignTeacher(@PathVariable UUID id, @Valid @RequestBody AssignTeacherRequest request) {
        return groups.assignTeacher(id, request.teacherUserId());
    }

    @PutMapping("/groups/{id}/schedule")
    @PreAuthorize("hasAnyRole('ADMIN','COORDINADOR')")
    @RequireScope(resource = "GROUP", id = "#id", action = AccessAction.WRITE)
    @Audited(action = "GROUP_SCHEDULE_REPLACE", entity = "Group", entityId = "#id")
    @Operation(summary = "Reemplaza el horario y regenera las sesiones futuras")
    public GroupView replaceSchedule(@PathVariable UUID id, @Valid @RequestBody ScheduleRequest request) {
        return groups.replaceSchedule(id, request.schedule().stream().map(SlotRequest::toSlot).toList());
    }

    @PostMapping("/groups/{id}/sessions/generate")
    @PreAuthorize("hasAnyRole('ADMIN','COORDINADOR')")
    @RequireScope(resource = "GROUP", id = "#id", action = AccessAction.WRITE)
    @Audited(action = "GROUP_SESSIONS_GENERATE", entity = "Group", entityId = "#id")
    @Operation(summary = "Genera las sesiones del grupo según horario, festivos y suspensiones (idempotente)")
    public GenerationResult generate(@PathVariable UUID id) {
        return groups.generateSessions(id);
    }

    @GetMapping("/groups/{id}/sessions")
    @RequireScope(resource = "GROUP", id = "#id")
    @Operation(summary = "Sesiones del grupo en un rango de fechas")
    public List<SessionView> sessions(@PathVariable UUID id,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return groups.sessions(id, from, to);
    }

    @PatchMapping("/sessions/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','COORDINADOR','DOCENTE')")
    @RequireScope(resource = "SESSION", id = "#id", action = AccessAction.WRITE)
    @Audited(action = "SESSION_STATUS_CHANGE", entity = "ClassSession", entityId = "#id")
    @Operation(summary = "Marca una sesión como dictada, cancelada (con motivo) o la reprograma")
    public SessionView changeStatus(@PathVariable UUID id, @Valid @RequestBody SessionStatusRequest request) {
        return groups.changeSessionStatus(id, request.status(), request.reason());
    }
}
