package co.edu.retencion.settings.application;

import java.time.Clock;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.error.ProblemCatalog;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.settings.SettingChanged;
import co.edu.retencion.settings.domain.AppSetting;
import co.edu.retencion.settings.domain.AppSettingRepository;

/**
 * Casos de uso de administración de parámetros. Solo se pueden editar claves existentes
 * (sembradas por migración) y el nuevo valor debe ser del mismo tipo JSON que el actual, para
 * que un error de digitación no convierta un umbral numérico en texto.
 */
@Service
public class SettingsAdminService {

    private final AppSettingRepository repository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public SettingsAdminService(AppSettingRepository repository, ObjectMapper objectMapper,
                                ApplicationEventPublisher events, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<SettingView> list() {
        return repository.findAllByOrderByKeyAsc().stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public SettingView get(String key) {
        return repository.findById(key).map(this::toView)
                .orElseThrow(() -> DomainException.notFound("Parámetro inexistente: " + key));
    }

    @Transactional
    public SettingView update(String key, JsonNode newValue, long expectedVersion) {
        AppSetting setting = repository.findById(key)
                .orElseThrow(() -> DomainException.notFound("Parámetro inexistente: " + key));
        if (setting.getVersion() != expectedVersion) {
            throw new DomainException(ProblemCatalog.PRECONDITION_FAILED,
                    "El parámetro cambió desde que lo leíste (versión actual " + setting.getVersion() + ")");
        }
        JsonNode current = parse(setting.getValue());
        if (newValue == null || newValue.getNodeType() != current.getNodeType()) {
            throw DomainException.badRequest("El valor debe ser de tipo " + current.getNodeType());
        }
        setting.change(newValue.toString(), CurrentUser.require().userId(), clock.instant());
        repository.saveAndFlush(setting);
        events.publishEvent(new SettingChanged(key));
        return toView(setting);
    }

    private SettingView toView(AppSetting s) {
        return new SettingView(s.getKey(), parse(s.getValue()), s.getDescription(), s.getVersion(), s.getUpdatedAt());
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("JSON de configuración inválido", ex);
        }
    }
}
