package co.edu.retencion.settings.infrastructure;

import java.time.Duration;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.springframework.transaction.event.TransactionalEventListener;

import co.edu.retencion.settings.SettingChanged;
import co.edu.retencion.settings.SettingsProvider;

/**
 * <b>Patrón estructural: Proxy (de caché).</b> Implementa la misma interfaz que el sujeto real
 * ({@link JpaSettingsProvider}) y se interpone para evitar una consulta SQL por cada lectura de
 * parámetro (el motor de riesgo y la asistencia los leerán miles de veces).
 *
 * <ul>
 *   <li>En esta instancia, la entrada se invalida al confirmarse un {@link SettingChanged}.</li>
 *   <li>En otras instancias, la caché expira sola tras un TTL corto (consistencia eventual).</li>
 * </ul>
 */
public class CachingSettingsProxy implements SettingsProvider {

    private final SettingsProvider target;
    private final Cache<String, Optional<JsonNode>> cache;

    public CachingSettingsProxy(SettingsProvider target, Duration ttl) {
        this.target = target;
        this.cache = Caffeine.newBuilder().maximumSize(1_000).expireAfterWrite(ttl).build();
    }

    @Override
    public Optional<JsonNode> value(String key) {
        return cache.get(key, target::value);
    }

    @TransactionalEventListener
    public void on(SettingChanged event) {
        cache.invalidate(event.key());
    }
}
