package co.edu.retencion.common.files;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Almacenamiento de archivos ({@code app.storage.*}).
 *
 * @param type  {@code local} (dev/test) o {@code s3} (producción, cualquier servicio compatible con S3)
 * @param local carpeta y URL base para las descargas firmadas locales
 * @param s3    endpoint, región, bucket y credenciales
 */
@ConfigurationProperties("app.storage")
public record StorageProperties(@DefaultValue("local") String type, @DefaultValue Local local, @DefaultValue S3 s3) {

    /**
     * @param directory  carpeta donde se guardan los objetos
     * @param publicBaseUrl URL base del backend (para armar la URL firmada)
     */
    public record Local(Path directory, @DefaultValue("http://localhost:8080") String publicBaseUrl) {
    }

    /**
     * @param endpoint     p. ej. {@code https://<proyecto>.supabase.co/storage/v1/s3} o {@code http://localhost:9000}
     * @param region       región (en Supabase, la del proyecto)
     * @param bucket       bucket privado
     * @param accessKey    clave de acceso
     * @param secretKey    secreto
     * @param createBucket crear el bucket al arrancar si no existe (útil con MinIO)
     */
    public record S3(String endpoint, @DefaultValue("us-east-1") String region, @DefaultValue("retencion") String bucket,
                     String accessKey, String secretKey, @DefaultValue("false") boolean createBucket) {

        @Override
        public String toString() {
            return "S3[endpoint=" + endpoint + ", bucket=" + bucket + "]";
        }
    }
}
