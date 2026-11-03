package co.edu.retencion.settings.infrastructure;

import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import co.edu.retencion.settings.domain.AppSettingRepository;

/**
 * Expone como {@code SettingsProvider} el proxy de caché, nunca el proveedor JPA directo.
 */
@Configuration(proxyBeanMethods = false)
public class SettingsConfig {

    @Bean
    CachingSettingsProxy settingsProvider(AppSettingRepository repository, ObjectMapper objectMapper,
                                          @Value("${app.settings.cache-ttl:30s}") Duration ttl) {
        return new CachingSettingsProxy(new JpaSettingsProvider(repository, objectMapper), ttl);
    }
}
