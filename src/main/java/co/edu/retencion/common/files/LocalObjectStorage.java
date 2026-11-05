package co.edu.retencion.common.files;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Almacenamiento en disco para desarrollo y pruebas. Imita las URL prefirmadas de S3: el token
 * lleva la clave, el vencimiento y una firma HMAC con una clave aleatoria del proceso, y lo
 * valida {@code FileDownloadController}.
 *
 * <p><b>Patrón estructural: Adapter.</b> Adapta el sistema de archivos local al puerto
 * {@link ObjectStorage}.
 *
 * <p>Asistido por IA (Claude, Anthropic).
 */
public class LocalObjectStorage implements ObjectStorage {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final Path root;
    private final String publicBaseUrl;
    private final Clock clock;
    private final byte[] signingKey = new byte[32];

    public LocalObjectStorage(Path root, String publicBaseUrl, Clock clock) {
        this.root = root;
        this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
        this.clock = clock;
        new SecureRandom().nextBytes(signingKey);
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Descarga verificada a partir de un token. */
    public record SignedDownload(String key, String fileName) {
    }

    @Override
    public void put(String key, byte[] content, String mediaType) {
        try {
            Files.write(resolve(key), content);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public void put(String key, java.nio.file.Path file, String mediaType) {
        try {
            Files.copy(file, resolve(key), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public byte[] get(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public URI presignedDownloadUrl(String key, Duration ttl, String downloadFileName) {
        long expires = clock.instant().plus(ttl).getEpochSecond();
        String payload = key + "|" + expires + "|" + downloadFileName;
        String token = B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + B64.encodeToString(hmac(payload));
        return URI.create(publicBaseUrl + "/api/v1/files/" + token);
    }

    /** Valida firma y vencimiento; vacío si el token no es válido o ya venció. */
    public Optional<SignedDownload> verify(String token) {
        int dot = token.indexOf('.');
        if (dot < 1) {
            return Optional.empty();
        }
        try {
            String payload = new String(B64D.decode(token.substring(0, dot)), StandardCharsets.UTF_8);
            byte[] signature = B64D.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(signature, hmac(payload))) {
                return Optional.empty();
            }
            String[] parts = payload.split("\\|", 3);
            if (parts.length != 3 || clock.instant().getEpochSecond() > Long.parseLong(parts[1])) {
                return Optional.empty();
            }
            return Optional.of(new SignedDownload(parts[0], parts[2]));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private Path resolve(String key) {
        if (!key.matches("^[A-Za-z0-9/_-]{8,200}$") || key.contains("..")) {
            throw new IllegalArgumentException("Clave de objeto inválida");
        }
        Path path = root.resolve(key.replace('/', '_')).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Clave de objeto inválida");
        }
        return path;
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
