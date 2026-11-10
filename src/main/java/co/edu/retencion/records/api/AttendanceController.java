package co.edu.retencion.records.api;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import co.edu.retencion.audit.Audited;
import co.edu.retencion.common.security.authorization.AccessAction;
import co.edu.retencion.common.security.authorization.RequireScope;
import co.edu.retencion.records.AttendanceStatus;
import co.edu.retencion.records.application.AttendanceService;
import co.edu.retencion.records.application.RecordsViews.AttendanceView;
import co.edu.retencion.records.application.RecordsViews.CounterView;

/**
 * Asistencia por sesión y contadores de faltas. Toma asistencia el docente del grupo (dentro de la
 * ventana de edición) o coordinación/administración (fuera de ella, con motivo).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Asistencia")
public class AttendanceController {

    private final AttendanceService attendance;
    /**
     * Bulkhead de la toma de lista: en un pico (todos los docentes a la misma hora) limita cuántas se procesan a
     * la vez para que las demás esperen turno sin tener tomada una conexión, en lugar de agotar el pool y fallar
     * por timeout. Ver docs/dimensionamiento.md.
     */
    private final io.github.resilience4j.bulkhead.Bulkhead bulkhead;

    public AttendanceController(AttendanceService attendance,
                                io.github.resilience4j.bulkhead.BulkheadRegistry bulkheads) {
        this.bulkhead = bulkheads.bulkhead("attendance");
        this.attendance = attendance;
    }

    public record EntryRequest(@NotNull UUID enrollmentId, @NotNull AttendanceStatus status) {
    }

    public record AttendanceRequest(@NotNull @Size(min = 1, max = 1000) List<@Valid EntryRequest> entries) {
    }

    @PutMapping("/sessions/{id}/attendance")
    @PreAuthorize("hasAnyRole('ADMIN','COORDINADOR','DOCENTE')")
    @RequireScope(resource = "SESSION", id = "#id", action = AccessAction.WRITE)
    @Audited(action = "ATTENDANCE_RECORD", entity = "ClassSession", entityId = "#id")
    @Operation(summary = "Registra o corrige la asistencia de la sesión (uno o todos los estudiantes del grupo)")
    public List<AttendanceView> record(@PathVariable UUID id, @Valid @RequestBody AttendanceRequest request,
                                       @RequestHeader(value = "X-Access-Reason", required = false) String reason) {
        return io.github.resilience4j.bulkhead.Bulkhead.decorateSupplier(bulkhead, () -> {
            attendance.preparePartition(id);
            return attendance.record(id, request.entries().stream()
                    .map(e -> new AttendanceService.Entry(e.enrollmentId(), e.status())).toList(), reason);
        }).get();
    }

    @GetMapping("/sessions/{id}/attendance")
    @PreAuthorize("hasAnyRole('ADMIN','COORDINADOR','CONSEJERO','PSICOLOGO','DOCENTE')")
    @RequireScope(resource = "SESSION", id = "#id")
    @Operation(summary = "Asistencia registrada en la sesión")
    public List<AttendanceView> ofSession(@PathVariable UUID id) {
        return attendance.ofSession(id);
    }

    @GetMapping("/students/{id}/attendance")
    @RequireScope(resource = "STUDENT", id = "#id")
    @Operation(summary = "Historial de asistencia del estudiante (el docente solo ve sus grupos)")
    public List<AttendanceView> ofStudent(@PathVariable UUID id) {
        return attendance.ofStudent(id);
    }

    @GetMapping("/students/{id}/absence-counters")
    @RequireScope(resource = "STUDENT", id = "#id")
    @Operation(summary = "Contadores de faltas del estudiante por materia")
    public List<CounterView> countersOfStudent(@PathVariable UUID id) {
        return attendance.countersOfStudent(id);
    }

    @GetMapping("/groups/{id}/absence-counters")
    @PreAuthorize("hasAnyRole('ADMIN','COORDINADOR','CONSEJERO','PSICOLOGO','DOCENTE')")
    @RequireScope(resource = "GROUP", id = "#id")
    @Operation(summary = "Contadores de faltas del grupo")
    public List<CounterView> countersOfGroup(@PathVariable UUID id) {
        return attendance.countersOfGroup(id);
    }

    @PostMapping("/groups/{id}/absence-counters/recalculate")
    @PreAuthorize("hasAnyRole('ADMIN','COORDINADOR')")
    @RequireScope(resource = "GROUP", id = "#id", action = AccessAction.WRITE)
    @Audited(action = "ABSENCE_COUNTERS_RECALCULATE", entity = "Group", entityId = "#id")
    @Operation(summary = "Recalcula los contadores del grupo desde los registros (p. ej. tras cambiar la política)")
    public List<CounterView> recalculate(@PathVariable UUID id) {
        return attendance.recalculateGroup(id);
    }
}
