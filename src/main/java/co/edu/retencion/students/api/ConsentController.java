package co.edu.retencion.students.api;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import co.edu.retencion.audit.Audited;
import co.edu.retencion.common.security.authorization.AccessAction;
import co.edu.retencion.common.security.authorization.RequireScope;
import co.edu.retencion.students.application.ConsentService;
import co.edu.retencion.students.application.StudentViews.ConsentView;
import co.edu.retencion.students.domain.DataConsent;

/** Consentimiento de tratamiento de datos (Ley 1581 de 2012). */
@RestController
@RequestMapping("/api/v1/students")
@Tag(name = "Consentimiento")
public class ConsentController {

    private final ConsentService consents;

    public ConsentController(ConsentService consents) {
        this.consents = consents;
    }

    public record ConsentRequest(@NotBlank @Pattern(regexp = "^[A-Za-z0-9.\\-]{1,20}$") String policyVersion,
                                 @NotNull DataConsent.Purpose purpose, boolean granted,
                                 @NotNull DataConsent.Channel channel) {
    }

    public record SelfConsentRequest(@NotBlank @Pattern(regexp = "^[A-Za-z0-9.\\-]{1,20}$") String policyVersion,
                                     @NotNull DataConsent.Purpose purpose, boolean granted) {
    }

    @PostMapping("/{id}/consents")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','COORDINADOR','CONSEJERO')")
    @RequireScope(resource = "STUDENT", id = "#id", action = AccessAction.WRITE)
    @Audited(action = "CONSENT_RECORD", entity = "Student", entityId = "#id")
    @Operation(summary = "Registra el otorgamiento o la revocación de un consentimiento")
    public ConsentView record(@PathVariable UUID id, @Valid @RequestBody ConsentRequest request) {
        return consents.record(id, request.policyVersion(), request.purpose(), request.granted(), request.channel());
    }

    @GetMapping("/{id}/consents")
    @RequireScope(resource = "STUDENT", id = "#id")
    @Operation(summary = "Historial de consentimientos (el más reciente por finalidad es el vigente)")
    public List<ConsentView> history(@PathVariable UUID id) {
        return consents.history(id);
    }

    @PostMapping("/me/consents")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ESTUDIANTE')")
    @Audited(action = "CONSENT_RECORD_SELF", entity = "Student")
    @Operation(summary = "Portal: el estudiante otorga o revoca su consentimiento")
    public ConsentView recordSelf(@Valid @RequestBody SelfConsentRequest request) {
        return consents.recordForCurrentStudent(request.policyVersion(), request.purpose(), request.granted());
    }

    @GetMapping("/me/consents")
    @PreAuthorize("hasRole('ESTUDIANTE')")
    @Operation(summary = "Portal: mi historial de consentimientos")
    public List<ConsentView> historySelf() {
        return consents.historyForCurrentStudent();
    }
}
