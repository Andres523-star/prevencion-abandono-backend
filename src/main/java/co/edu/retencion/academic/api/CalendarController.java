package co.edu.retencion.academic.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
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

import co.edu.retencion.academic.application.AcademicViews.GenerationResult;
import co.edu.retencion.academic.application.AcademicViews.HolidayView;
import co.edu.retencion.academic.application.AcademicViews.SuspensionView;
import co.edu.retencion.academic.application.AcademicViews.TermView;
import co.edu.retencion.academic.application.CalendarService;
import co.edu.retencion.academic.application.GroupService;
import co.edu.retencion.academic.domain.Term;
import co.edu.retencion.audit.Audited;

/** Calendario académico: periodos, festivos y suspensiones de clase. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Calendario académico")
public class CalendarController {

    private final CalendarService calendar;
    private final GroupService groups;

    public CalendarController(CalendarService calendar, GroupService groups) {
        this.calendar = calendar;
        this.groups = groups;
    }

    public record TermRequest(@NotBlank @Pattern(regexp = "^[A-Za-z0-9-]{3,20}$") String code,
                              @NotBlank @Size(max = 120) String name, @NotNull LocalDate startDate,
                              @NotNull LocalDate endDate, @NotNull Term.Status status) {
    }

    public record HolidayRequest(@NotNull LocalDate date, @NotBlank @Size(max = 120) String name) {
    }

    /** @param programId vacío = suspensión para toda la universidad */
    public record SuspensionRequest(@NotNull LocalDate startDate, @NotNull LocalDate endDate, UUID programId,
                                    @NotBlank @Size(max = 300) String reason) {
    }

    @GetMapping("/terms")
    @Operation(summary = "Lista los periodos académicos")
    public List<TermView> terms() {
        return calendar.listTerms();
    }

    @GetMapping("/terms/{id}")
    public TermView term(@PathVariable UUID id) {
        return calendar.getTerm(id);
    }

    @PostMapping("/terms")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "TERM_CREATE", entity = "Term", entityId = "#result.id")
    @Operation(summary = "Crea un periodo académico")
    public TermView createTerm(@Valid @RequestBody TermRequest request) {
        return calendar.createTerm(request.code(), request.name(), request.startDate(), request.endDate(), request.status());
    }

    @PutMapping("/terms/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "TERM_UPDATE", entity = "Term", entityId = "#id")
    @Operation(summary = "Edita un periodo (un periodo cerrado no se reabre)")
    public TermView updateTerm(@PathVariable UUID id, @Valid @RequestBody TermRequest request) {
        return calendar.updateTerm(id, request.name(), request.startDate(), request.endDate(), request.status());
    }

    @PostMapping("/terms/{id}/sessions/generate")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "TERM_SESSIONS_GENERATE", entity = "Term", entityId = "#id")
    @Operation(summary = "Genera las sesiones de todos los grupos del periodo (idempotente)")
    public GenerationResult generateForTerm(@PathVariable UUID id) {
        return groups.generateSessionsForTerm(id);
    }

    @GetMapping("/holidays")
    @Operation(summary = "Festivos del año")
    public List<HolidayView> holidays(@RequestParam int year) {
        return calendar.holidays(year);
    }

    @PostMapping("/holidays")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "HOLIDAY_CREATE", entity = "Holiday", entityId = "#request.date()")
    @Operation(summary = "Registra un festivo y cancela las sesiones programadas de ese día")
    public HolidayView addHoliday(@Valid @RequestBody HolidayRequest request) {
        return calendar.addHoliday(request.date(), request.name());
    }

    @PostMapping("/holidays/colombia/{year}")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "HOLIDAY_LOAD_COLOMBIA", entity = "Holiday", entityId = "#year")
    @Operation(summary = "Carga los festivos de Colombia del año (Ley Emiliani)")
    public List<HolidayView> loadColombian(@PathVariable int year) {
        return calendar.loadColombianHolidays(year);
    }

    @DeleteMapping("/holidays/{date}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "HOLIDAY_DELETE", entity = "Holiday", entityId = "#date")
    @Operation(summary = "Elimina un festivo (las sesiones ya canceladas no se reactivan solas)")
    public void removeHoliday(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        calendar.removeHoliday(date);
    }

    @GetMapping("/class-suspensions")
    @Operation(summary = "Suspensiones de clase en un rango de fechas")
    public List<SuspensionView> suspensions(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return calendar.suspensions(from, to);
    }

    @PostMapping("/class-suspensions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') or (hasRole('COORDINADOR') and #request.programId() != null "
            + "and @abac.canAccess('PROGRAM', #request.programId(), 'WRITE'))")
    @Audited(action = "CLASS_SUSPENSION_CREATE", entity = "ClassSuspension", entityId = "#result.id")
    @Operation(summary = "Suspende clases (global: ADMIN; de un programa: ADMIN o su coordinador)")
    public SuspensionView suspend(@Valid @RequestBody SuspensionRequest request) {
        return calendar.suspend(request.startDate(), request.endDate(), request.programId(), request.reason());
    }
}
