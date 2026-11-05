package co.edu.retencion.common.state;

import java.time.Duration;
import java.util.Optional;

/**
 * Caché de texto compartida entre instancias (producto de {@link DistributedStateFactory}). La
 * invalidación es por <em>generación</em>: quien usa la caché incluye {@link #generation()} en la
 * clave y, al cambiar los datos, llama a {@link #nextGeneration()}. Así no hay que recorrer ni borrar
 * claves; las viejas quedan inalcanzables y vencen solas.
 */
public interface SharedCache {

    Optional<String> get(String key);

    void put(String key, String value, Duration ttl);

    long generation();

    long nextGeneration();
}
