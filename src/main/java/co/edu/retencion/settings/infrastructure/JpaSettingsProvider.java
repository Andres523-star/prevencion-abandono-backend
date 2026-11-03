package co.edu.retencion.settings.infrastructure;

import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.retencion.settings.SettingsProvider;
import co.edu.retencion.settings.domain.AppSettingRepository;

/**
 * Sujeto real del proxy: lee cada parámetro de la base de datos. No es un bean directo; lo
 * envuelve {@link CachingSettingsProxy}.
 */
public class JpaSettingsProvider implements SettingsProvider {

    private final AppSettingRepository repository;
    private final ObjectMapper objectMapper;

    public JpaSettingsProvider(AppSettingRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<JsonNode> value(String key) {
        return repository.findById(key).map(setting -> parse(setting.getValue()));
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Valor de configuración con JSON inválido", ex);
        }
    }
}
