package co.edu.retencion.ai.api;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import co.edu.retencion.ai.application.AiUsageService;
import co.edu.retencion.ai.application.AiUsageService.DailyUsage;
import co.edu.retencion.ai.application.ResourceCatalogService;
import co.edu.retencion.ai.application.ResourceCatalogService.ResourceData;
import co.edu.retencion.ai.application.ResourceCatalogService.ResourceView;
import co.edu.retencion.ai.application.RetentionAgentService;
import co.edu.retencion.ai.application.RetentionAgentService.AgentRunView;
import co.edu.retencion.ai.domain.SupportResource;
import co.edu.retencion.audit.Audited;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.common.security.authorization.AccessAction;
import co.edu.retencion.common.security.authorization.RequireScope;
import co.edu.retencion.common.security.authorization.ScopeAuthorizationService;

/**
 * Catálogo de recursos de apoyo, consumo de la IA y agente de retención. El agente solo produce
 * borradores: no envía mensajes ni ejecuta acciones.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "IA y recursos de apoyo")
public class AiController {

    private final ResourceCatalogService catalog;
    private final RetentionAgentService agent;
    private final AiUsageService usage;
    private final ScopeAuthorizationService scope;

    public AiController(ResourceCatalogService catalog, RetentionAgentService agent, AiUsageService usage,
                        ScopeAuthorizationService scope) {
        this.scope = scope;
        this.catalog = catalog;
        this.agent = agent;
        this.usage = usage;
    }

    public record ResourceRequest(@NotBlank @Size(max = 150) String name, @NotNull SupportResource.Category category,
                                  @NotBlank @Size(max = 1000) String description,
                                  SupportResource.Eligibility eligibility,
                                  @NotBlank @Size(max = 500) String eligibilityText,
                                  @NotBlank @Size(max = 200) String contact, Boolean active) {
        ResourceData data() {
            return new ResourceData(name, category, description, eligibility, eligibilityText, contact, active);
        }
    }

    public record CreateResourceRequest(@NotBlank @Pattern(regexp = "^[A-Z0-9]+(-[A-Z0-9]+)*$") @Size(max = 40) String id,
                                        @Valid @NotNull ResourceRequest resource) {
    }

    @GetMapping("/support-resources")
    @PreAuthorize("hasAnyRole('ADMIN','CONSEJERO','PSICOLOGO','COORDINADOR','DOCENTE')")
    @Operation(summary = "Catálogo de recursos de apoyo de la universidad")
    public List<ResourceView> resources() {
        return catalog.list();
    }

    @PostMapping("/support-resources")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "SUPPORT_RESOURCE_CREATE", entity = "SupportResource", entityId = "#request.id")
    @Operation(summary = "Agrega un recurso al catálogo")
    public ResourceView create(@Valid @RequestBody CreateResourceRequest request) {
        return catalog.create(request.id(), request.resource().data());
    }

    @PutMapping("/support-resources/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "SUPPORT_RESOURCE_UPDATE", entity = "SupportResource", entityId = "#id")
    @Operation(summary = "Edita un recurso del catálogo (o lo desactiva)")
    public ResourceView update(@PathVariable String id, @Valid @RequestBody ResourceRequest request) {
        return catalog.update(id, request.data());
    }

    @GetMapping("/ai/usage")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Consumo de tokens del día y presupuesto")
    public DailyUsage usageToday() {
        return usage.usageToday();
    }

    @PostMapping("/students/{id}/retention-agent/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','CONSEJERO','PSICOLOGO')")
    @RequireScope(resource = "STUDENT", id = "#id")
    @Audited(action = "RETENTION_AGENT_DRAFT", entity = "Student", entityId = "#id")
    @Operation(summary = "Pide al agente un borrador de plan de retención (solo borrador, revisión humana)")
    public AgentRunView draft(@PathVariable UUID id) {
        return agent.draft(id, CurrentUser.require().userId());
    }

    @GetMapping("/retention-agent/drafts/{runId}")
    @PreAuthorize("hasAnyRole('ADMIN','CONSEJERO','PSICOLOGO')")
    @Operation(summary = "Borrador del agente con las herramientas que consultó")
    public AgentRunView run(@PathVariable UUID runId) {
        AgentRunView view = agent.find(runId).orElseThrow(() -> DomainException.notFound("Borrador no encontrado"));
        scope.check("STUDENT", view.studentId(), AccessAction.READ);
        return view;
    }
}
