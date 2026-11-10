package co.edu.retencion.privacy.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.databind.JsonNode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

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
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.privacy.application.DataRequestService;
import co.edu.retencion.privacy.application.DataRequestService.DownloadView;
import co.edu.retencion.privacy.application.DataRequestService.RequestView;
import co.edu.retencion.privacy.application.ProcessingActivities;
import co.edu.retencion.privacy.domain.DataSubjectRequest;

/** Derechos del titular y registro de actividades de tratamiento. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Privacidad")
public class PrivacyController {

    private final DataRequestService requests;
    private final ProcessingActivities activities;

    public PrivacyController(DataRequestService requests, ProcessingActivities activities) {
        this.requests = requests;
        this.activities = activities;
    }

    public record SubmitRequest(@NotNull DataSubjectRequest.Type type, @Size(max = 2000) String detail) {
        @Override
        public String toString() {
            return "SubmitRequest[" + type + "]";
        }
    }

    public record DecisionRequest(@Size(max = 2000) String comment) {
        @Override
        public String toString() {
            return "DecisionRequest[***]";
        }
    }

    @PostMapping("/students/me/data-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ESTUDIANTE')")
    @Audited(action = "DATA_REQUEST_SUBMIT", entity = "DataSubjectRequest", entityId = "#result.id")
    @Operation(summary = "Radica una consulta, rectificación o supresión de mis datos")
    public RequestView submit(@Valid @RequestBody SubmitRequest request) {
        return requests.submit(CurrentUser.require(), request.type(), request.detail());
    }

    @GetMapping("/students/me/data-requests")
    @PreAuthorize("hasRole('ESTUDIANTE')")
    @Operation(summary = "Mis solicitudes y su estado")
    public List<RequestView> mine() {
        return requests.mine(CurrentUser.require());
    }

    @GetMapping("/data-requests/{id}/export-url")
    @PreAuthorize("hasRole('ESTUDIANTE')")
    @SensitiveRead(entity = "DataSubjectRequest", entityId = "#id", requireReason = false)
    @Operation(summary = "URL de 10 minutos para descargar la copia de mis datos")
    public DownloadView export(@PathVariable UUID id) {
        return requests.exportUrl(CurrentUser.require(), id);
    }

    @GetMapping("/privacy/data-requests")
    @PreAuthorize("hasRole('ADMIN')")
    @SensitiveRead(entity = "DataSubjectRequest", requireReason = false)
    @Operation(summary = "Bandeja de solicitudes, por fecha límite")
    public List<RequestView> queue(@RequestParam(required = false) DataSubjectRequest.Status status) {
        return requests.queue(status);
    }

    @PostMapping("/privacy/data-requests/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "DATA_REQUEST_APPROVE", entity = "DataSubjectRequest", entityId = "#id")
    @Operation(summary = "Aprueba (una consulta entrega la copia en el acto)")
    public RequestView approve(@PathVariable UUID id, @Valid @RequestBody(required = false) DecisionRequest request) {
        return requests.approve(CurrentUser.require(), id, request == null ? null : request.comment());
    }

    @PostMapping("/privacy/data-requests/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "DATA_REQUEST_REJECT", entity = "DataSubjectRequest", entityId = "#id")
    @Operation(summary = "Rechaza con motivo")
    public RequestView reject(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
        return requests.reject(CurrentUser.require(), id, request.comment());
    }

    @PostMapping("/privacy/data-requests/{id}/execute")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "DATA_REQUEST_EXECUTE", entity = "DataSubjectRequest", entityId = "#id")
    @Operation(summary = "Ejecuta una rectificación o supresión aprobada (la supresión, otra persona)")
    public RequestView execute(@PathVariable UUID id, @Valid @RequestBody(required = false) DecisionRequest request) {
        return requests.execute(CurrentUser.require(), id, request == null ? null : request.comment());
    }

    @GetMapping("/privacy/processing-activities")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Registro de actividades de tratamiento (finalidades, bases legales, encargados, retención)")
    public JsonNode processingActivities() {
        return activities.registry();
    }
}
