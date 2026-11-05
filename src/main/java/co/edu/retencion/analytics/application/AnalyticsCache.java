package co.edu.retencion.analytics.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Component;

import co.edu.retencion.common.state.DistributedStateFactory;
import co.edu.retencion.common.state.SharedCache;
import co.edu.retencion.settings.SettingsProvider;

/**
 * Caché de respuestas de analítica (Redis en producción). La clave incluye la generación: cuando un
 * refresco cambia los datos, sube la generación y todas las respuestas anteriores quedan inalcanzables.
 * La clave también lleva el alcance del usuario, así dos coordinadores nunca comparten respuesta.
 */
@Component
class AnalyticsCache {

    private final SharedCache cache;
    private final ObjectMapper mapper;
    private final SettingsProvider settings;
    private final Counter hits;
    private final Counter misses;

    AnalyticsCache(DistributedStateFactory factory, ObjectMapper mapper, SettingsProvider settings, MeterRegistry meters) {
        this.cache = factory.createCache("analytics");
        this.mapper = mapper;
        this.settings = settings;
        this.hits = meters.counter("analytics.cache", "result", "hit");
        this.misses = meters.counter("analytics.cache", "result", "miss");
    }

    <T> T get(String endpoint, Object keyParts, TypeReference<T> type, Supplier<T> loader) {
        String key = cache.generation() + ":" + endpoint + ":" + hash(keyParts);
        Optional<String> cached = cache.get(key);
        if (cached.isPresent()) {
            try {
                T value = mapper.readValue(cached.get(), type);
                hits.increment();
                return value;
            } catch (JsonProcessingException ex) {
                // Formato de una versión anterior: se recalcula.
            }
        }
        misses.increment();
        T value = loader.get();
        try {
            cache.put(key, mapper.writeValueAsString(value),
                    Duration.ofSeconds(Math.max(1, settings.getInt("analytics.cache-ttl-seconds", 300))));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Respuesta de analítica no serializable", ex);
        }
        return value;
    }

    void invalidate() {
        cache.nextGeneration();
    }

    private String hash(Object keyParts) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsString(keyParts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
