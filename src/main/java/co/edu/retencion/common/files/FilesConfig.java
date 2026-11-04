package co.edu.retencion.common.files;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import co.edu.retencion.common.security.PublicEndpoints;

/**
 * Selecciona almacenamiento ({@code app.storage.type}) y antivirus ({@code app.antivirus.mode}).
 *
 * <p><b>Patrón creacional: Factory Method.</b> Cada método de fábrica decide qué implementación
 * del puerto construir según la configuración, sin que los servicios conozcan la clase concreta.
 *
 * <p>Asistido por IA (Claude, Anthropic).
 */
@Configuration(proxyBeanMethods = false)
public class FilesConfig {

    /**
     * @param mode    {@code clamav} (producción) o {@code signature} (solo EICAR, desarrollo y pruebas)
     * @param host    host de clamd
     * @param port    puerto de clamd
     * @param timeout tiempo máximo de escaneo
     */
    @ConfigurationProperties("app.antivirus")
    public record AntivirusProperties(@DefaultValue("clamav") String mode, @DefaultValue("localhost") String host,
                                      @DefaultValue("3310") int port, @DefaultValue("20s") Duration timeout) {
    }

    @Bean
    @ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
    ObjectStorage s3ObjectStorage(StorageProperties properties) {
        return new S3ObjectStorage(properties.s3());
    }

    @Bean
    @ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
    LocalObjectStorage localObjectStorage(StorageProperties properties, Clock clock) {
        Path directory = properties.local().directory() != null ? properties.local().directory()
                : Path.of(System.getProperty("java.io.tmpdir"), "retencion-storage");
        return new LocalObjectStorage(directory.toAbsolutePath(), properties.local().publicBaseUrl(), clock);
    }

    /** La descarga firmada local no lleva JWT: la protege la firma del token (como en S3). */
    @Bean
    @ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
    PublicEndpoints localFilesPublicEndpoints() {
        return new PublicEndpoints(List.of("/api/v1/files/*"));
    }

    @Bean
    MalwareScanner malwareScanner(AntivirusProperties properties) {
        return "signature".equalsIgnoreCase(properties.mode())
                ? new SignatureScanner()
                : new ClamAvScanner(properties.host(), properties.port(), properties.timeout());
    }
}
