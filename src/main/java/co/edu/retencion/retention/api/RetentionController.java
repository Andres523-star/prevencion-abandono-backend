package co.edu.retencion.retention.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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
import co.edu.retencion.common.security.authorization.RequireScope;
import co.edu.retencion.retention.application.ReentryCampaignService;
import co.edu.retencion.retention.application.ReentryCampaignService.CampaignView;
import co.edu.retencion.retention.application.RetentionService;
import co.edu.retencion.retention.application.RetentionService.CaseView;
import co.edu.retencion.retention.application.RetentionService.ContactView;
import co.edu.retencion.retention.application.RetentionService.FollowUpView;
import co.edu.retencion.retention.application.RetentionService.FunnelView;
import co.edu.retencion.retention.application.RetentionService.NoteView;
import co.edu.retencion.retention.application.RetentionService.TaskView;
import co.edu.retencion.retention.domain.ContactAttempt;
import co.edu.retencion.retention.domain.RetentionCase;

/** Casos de retención, embudo y campaña de reingreso. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Retención")
public class RetentionController {

    static final String STAFF = "hasAnyRole('ADMIN','CONSEJERO','PSICOLOGO','COORDINADOR')";

    private final RetentionService retention;
    private final ReentryCampaignService campaigns;

    public RetentionController(RetentionService retention, ReentryCampaignService campaigns) {
        this.retention = retention;
        this.campaigns = campaigns;
    }

    public record OpenRequest(@NotNull UUID studentId) {
    }

    public record SelfReportRequest(@Size(max = 2000) String message) {
        @Override
        public String toString() {
            return "SelfReportRequest[***]";
        }
    }

    public record AdvanceRequest(@NotNull RetentionCase.Stage stage) {
    }

    public record ContactRequest(@NotNull ContactAttempt.Channel channel, @NotNull ContactAttempt.Result result,
                                 @Size(max = 2000) String notes) {
        @Override
        public String toString() {
            return "ContactRequest[" + channel + "]";
        }
    }

    public record NoteRequest(@NotBlank @Size(max = 8000) String text) {
        @Override
        public String toString() {
            return "NoteRequest[***]";
        }
    }

    public record TaskRequest(@NotBlank @Size(max = 200) String title, @NotNull LocalDate dueDate) {
    }

    public record CloseRequest(@NotNull RetentionCase.Outcome outcome, @Size(max = 40) String causeCode,
                               Map<String, Object> exitSurvey) {
        @Override
        public String toString() {
            return "CloseRequest[" + outcome + "]";
        }
    }

    @PostMapping("/retention-cases")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(STAFF)
    @Audited(action = "RETENTION_CASE_OPEN", entity = "Student", entityId = "#request.studentId")
    @Operation(summary = "Abre un caso de retención (consejero)")
    public CaseView open(@Valid @RequestBody OpenRequest request) {
        return retention.openByCounselor(request.studentId());
    }

    @PostMapping("/students/me/retention-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ESTUDIANTE')")
    @Audited(action = "RETENTION_SELF_REPORT", entity = "RetentionCase")
    @Operation(summary = "Necesito ayuda / estoy pensando en retirarme (abre o reutiliza el caso)")
    public CaseView selfReport(@Valid @RequestBody(required = false) SelfReportRequest request) {
        return retention.selfReport(request == null ? null : request.message());
    }

    @GetMapping("/retention-cases/{id}")
    @PreAuthorize(STAFF)
    @SensitiveRead(entity = "RetentionCase", entityId = "#id", requireReason = false)
    @Operation(summary = "Detalle del caso (contactos, tareas, seguimientos)")
    public CaseView get(@PathVariable UUID id) {
        return retention.get(id);
    }

    @GetMapping("/students/{id}/retention-cases")
    @PreAuthorize(STAFF)
    @RequireScope(resource = "STUDENT", id = "#id")
    @SensitiveRead(entity = "RetentionCase", entityId = "#id", requireReason = false)
    @Operation(summary = "Casos de retención del estudiante")
    public List<CaseView> ofStudent(@PathVariable UUID id) {
        return retention.ofStudent(id);
    }

    @PostMapping("/retention-cases/{id}/advance")
    @PreAuthorize(STAFF)
    @Audited(action = "RETENTION_CASE_ADVANCE", entity = "RetentionCase", entityId = "#id")
    @Operation(summary = "Avanza a la etapa siguiente (sin saltos ni retrocesos)")
    public CaseView advance(@PathVariable UUID id, @Valid @RequestBody AdvanceRequest request) {
        return retention.advance(id, request.stage());
    }

    @PostMapping("/retention-cases/{id}/contacts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(STAFF)
    @Audited(action = "RETENTION_CONTACT", entity = "RetentionCase", entityId = "#id")
    @Operation(summary = "Registra un intento de contacto")
    public ContactView contact(@PathVariable UUID id, @Valid @RequestBody ContactRequest request) {
        return retention.recordContact(id, request.channel(), request.result(), request.notes());
    }

    @PostMapping("/retention-cases/{id}/interview-notes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(STAFF)
    @Audited(action = "RETENTION_INTERVIEW_NOTE", entity = "RetentionCase", entityId = "#id")
    @Operation(summary = "Nota de entrevista (restringida)")
    public NoteView interviewNote(@PathVariable UUID id, @Valid @RequestBody NoteRequest request) {
        return retention.addInterviewNote(id, request.text());
    }

    @GetMapping("/retention-cases/{id}/interview-notes")
    @PreAuthorize(STAFF)
    @SensitiveRead(entity = "RetentionInterviewNote", entityId = "#id")
    @Operation(summary = "Notas de entrevista (consejero asignado o PSICOLOGO; requiere X-Access-Reason)")
    public List<NoteView> interviewNotes(@PathVariable UUID id) {
        return retention.interviewNotes(id);
    }

    @PostMapping("/retention-cases/{id}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(STAFF)
    @Audited(action = "RETENTION_TASK_CREATE", entity = "RetentionCase", entityId = "#id")
    @Operation(summary = "Agrega una tarea con fecha al plan del caso")
    public TaskView task(@PathVariable UUID id, @Valid @RequestBody TaskRequest request) {
        return retention.addTask(id, request.title(), request.dueDate());
    }

    @PostMapping("/retention-cases/{id}/tasks/{taskId}/done")
    @PreAuthorize(STAFF)
    @Audited(action = "RETENTION_TASK_DONE", entity = "RetentionCase", entityId = "#id")
    @Operation(summary = "Marca una tarea como hecha")
    public TaskView taskDone(@PathVariable UUID id, @PathVariable UUID taskId) {
        return retention.completeTask(id, taskId);
    }

    @PostMapping("/retention-cases/{id}/follow-ups/{followUpId}/done")
    @PreAuthorize(STAFF)
    @Audited(action = "RETENTION_FOLLOW_UP_DONE", entity = "RetentionCase", entityId = "#id")
    @Operation(summary = "Registra el seguimiento como hecho")
    public FollowUpView followUpDone(@PathVariable UUID id, @PathVariable UUID followUpId) {
        return retention.completeFollowUp(id, followUpId);
    }

    @PostMapping("/retention-cases/{id}/close")
    @PreAuthorize(STAFF)
    @Audited(action = "RETENTION_CASE_CLOSE", entity = "RetentionCase", entityId = "#id")
    @Operation(summary = "Cierra el caso (RETIRADO exige causa y encuesta; actualiza el estado del estudiante)")
    public CaseView close(@PathVariable UUID id, @Valid @RequestBody CloseRequest request) {
        return retention.close(id, request.outcome(), request.causeCode(), request.exitSurvey());
    }

    @GetMapping("/retention/funnel")
    @PreAuthorize(STAFF)
    @Operation(summary = "Embudo de retención por programa y periodo")
    public FunnelView funnel(@RequestParam(required = false) UUID programId, @RequestParam(required = false) UUID termId) {
        return retention.funnel(programId, termId);
    }

    @PostMapping("/retention/follow-ups/remind")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "RETENTION_REMINDERS_RUN", entity = "RetentionCase")
    @Operation(summary = "Ejecuta ahora los recordatorios de seguimiento vencidos")
    public int remind() {
        return retention.remindDueFollowUps();
    }

    @GetMapping("/retention/campaigns")
    @PreAuthorize("hasAnyRole('ADMIN','COORDINADOR')")
    @Operation(summary = "Campañas de reingreso")
    public List<CampaignView> campaigns() {
        return campaigns.list();
    }

    @PostMapping("/retention/campaigns")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','COORDINADOR')")
    @Audited(action = "REENTRY_CAMPAIGN_DRAFT", entity = "ReentryCampaign", entityId = "#result.id")
    @Operation(summary = "Arma ahora un borrador de campaña de reingreso")
    public CampaignView draft() {
        return campaigns.draft(co.edu.retencion.common.security.CurrentUser.require().userId());
    }

    @PostMapping("/retention/campaigns/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN','COORDINADOR')")
    @Audited(action = "REENTRY_CAMPAIGN_APPROVE", entity = "ReentryCampaign", entityId = "#id")
    @Operation(summary = "Aprueba la campaña: crea los contactos de reingreso para los consejeros")
    public CampaignView approve(@PathVariable UUID id) {
        return campaigns.review(id, true);
    }

    @PostMapping("/retention/campaigns/{id}/discard")
    @PreAuthorize("hasAnyRole('ADMIN','COORDINADOR')")
    @Audited(action = "REENTRY_CAMPAIGN_DISCARD", entity = "ReentryCampaign", entityId = "#id")
    @Operation(summary = "Descarta la campaña")
    public CampaignView discard(@PathVariable UUID id) {
        return campaigns.review(id, false);
    }
}
