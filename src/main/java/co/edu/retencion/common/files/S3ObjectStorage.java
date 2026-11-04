package co.edu.retencion.common.files;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;

/**
 * <b>Patrón estructural: Adapter.</b> Adapta el cliente MinIO (protocolo S3) al puerto
 * {@link ObjectStorage}. Sirve para MinIO, Supabase Storage (endpoint S3), Cloudflare R2 o AWS S3:
 * el bucket debe ser privado; las descargas usan URL prefirmadas.
 */
public class S3ObjectStorage implements ObjectStorage {

    private final MinioClient client;
    private final String bucket;

    public S3ObjectStorage(StorageProperties.S3 config) {
        this.client = MinioClient.builder()
                .endpoint(config.endpoint())
                .region(config.region())
                .credentials(config.accessKey(), config.secretKey())
                .build();
        this.bucket = config.bucket();
        if (config.createBucket()) {
            run(() -> {
                if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                    client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                }
                return null;
            });
        }
    }

    @Override
    public void put(String key, byte[] content, String mediaType) {
        run(() -> client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                .stream(new ByteArrayInputStream(content), content.length, -1).contentType(mediaType).build()));
    }

    @Override
    public byte[] get(String key) {
        return run(() -> {
            try (InputStream in = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
                return in.readAllBytes();
            }
        });
    }

    @Override
    public void delete(String key) {
        run(() -> {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
            return null;
        });
    }

    @Override
    public URI presignedDownloadUrl(String key, Duration ttl, String downloadFileName) {
        return URI.create(run(() -> client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.GET).bucket(bucket).object(key).expiry((int) ttl.toSeconds())
                .extraQueryParams(Map.of("response-content-disposition", "attachment; filename=\"" + downloadFileName + "\""))
                .build())));
    }

    @FunctionalInterface
    private interface Call<T> {
        T call() throws Exception;
    }

    private static <T> T run(Call<T> call) {
        try {
            return call.call();
        } catch (Exception ex) {
            throw new IllegalStateException("Error del almacenamiento de objetos: " + ex.getClass().getSimpleName(), ex);
        }
    }
}
