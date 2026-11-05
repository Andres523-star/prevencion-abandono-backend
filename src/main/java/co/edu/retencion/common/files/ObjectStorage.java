package co.edu.retencion.common.files;

import java.net.URI;
import java.time.Duration;

/**
 * Puerto de almacenamiento de objetos (evidencias, adjuntos). Implementaciones:
 * {@link S3ObjectStorage} (MinIO, Supabase Storage, Cloudflare R2, AWS S3...) y
 * {@link LocalObjectStorage} (desarrollo y pruebas). Los objetos nunca son públicos: solo se
 * descargan con una URL firmada de corta duración.
 */
public interface ObjectStorage {

    void put(String key, byte[] content, String mediaType);

    /** Sube un archivo sin cargarlo en memoria (reportes grandes). */
    void put(String key, java.nio.file.Path file, String mediaType);

    byte[] get(String key);

    void delete(String key);

    /** URL de descarga firmada que vence en {@code ttl}. */
    URI presignedDownloadUrl(String key, Duration ttl, String downloadFileName);
}
