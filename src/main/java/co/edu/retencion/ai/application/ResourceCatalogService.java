package co.edu.retencion.ai.application;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.ai.ResourceCatalog;
import co.edu.retencion.ai.SupportResourceInfo;
import co.edu.retencion.ai.domain.SupportResource;
import co.edu.retencion.ai.domain.SupportResourceRepository;
import co.edu.retencion.common.error.DomainException;

/**
 * Catálogo de recursos de apoyo (RAG simple): la "recuperación" es un filtro determinista por
 * elegibilidad, y la IA solo recibe y puede citar lo recuperado.
 */
@Service
public class ResourceCatalogService implements ResourceCatalog {

    private final SupportResourceRepository resources;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ResourceCatalogService(SupportResourceRepository resources, ObjectMapper mapper, Clock clock) {
        this.resources = resources;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Vista de administración (incluye elegibilidad estructurada). */
    public record ResourceView(String id, String name, SupportResource.Category category, String description,
                               SupportResource.Eligibility eligibility, String eligibilityText, String contact,
                               boolean active, long version) {
    }

    /**
     * Datos de alta o edición.
     */
    public record ResourceData(String name, SupportResource.Category category, String description,
                               SupportResource.Eligibility eligibility, String eligibilityText, String contact,
                               Boolean active) {
    }

    @Override
    @Transactional(readOnly = true)
    public List<SupportResourceInfo> eligible(EligibilityProfile p) {
        return resources.findByActiveTrueOrderByIdAsc().stream()
                .filter(r -> eligibility(r).admits(p.programCode(), p.semester(), p.stratum(), p.works(), p.transport()))
                .map(ResourceCatalogService::info).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SupportResourceInfo> find(String resourceId) {
        return resources.findById(resourceId).map(ResourceCatalogService::info);
    }

    @Transactional(readOnly = true)
    public List<ResourceView> list() {
        return resources.findAllByOrderByIdAsc().stream().map(this::view).toList();
    }

    @Transactional
    public ResourceView create(String id, ResourceData data) {
        if (resources.existsById(id)) {
            throw DomainException.conflict("Ya existe un recurso con ese id");
        }
        return view(resources.save(SupportResource.create(id, data.name(), data.category(), data.description(),
                json(data.eligibility()), data.eligibilityText(), data.contact(), clock.instant())));
    }

    @Transactional
    public ResourceView update(String id, ResourceData data) {
        SupportResource r = resources.findById(id).orElseThrow(() -> DomainException.notFound("Recurso no encontrado"));
        r.update(data.name(), data.category(), data.description(), json(data.eligibility()), data.eligibilityText(),
                data.contact(), data.active() == null || data.active(), clock.instant());
        return view(resources.saveAndFlush(r));
    }

    SupportResource.Eligibility eligibility(SupportResource r) {
        try {
            return mapper.readValue(r.getEligibility(), SupportResource.Eligibility.class);
        } catch (JsonProcessingException ex) {
            // Una regla ilegible no debe ofrecer el recurso a quien no corresponde: se excluye.
            return new SupportResource.Eligibility(List.of("__INVALIDA__"), null, null, null, null, null);
        }
    }

    private String json(SupportResource.Eligibility eligibility) {
        try {
            return eligibility == null ? "{}" : mapper.writeValueAsString(eligibility);
        } catch (JsonProcessingException ex) {
            throw DomainException.badRequest("Reglas de elegibilidad inválidas");
        }
    }

    private ResourceView view(SupportResource r) {
        return new ResourceView(r.getId(), r.getName(), r.getCategory(), r.getDescription(), eligibility(r),
                r.getEligibilityText(), r.getContact(), r.isActive(), r.getVersion());
    }

    static SupportResourceInfo info(SupportResource r) {
        return new SupportResourceInfo(r.getId(), r.getName(), r.getCategory().name(), r.getDescription(),
                r.getEligibilityText(), r.getContact());
    }
}
