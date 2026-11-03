package co.edu.retencion.common.crypto;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * <b>Patrón estructural: Proxy.</b> Se interpone entre la aplicación y el KMS real con la misma
 * interfaz {@link KmsProvider}, controlando el acceso a un recurso costoso (cada llamada a Vault
 * es una petición de red):
 * <ul>
 *   <li>Reutiliza la DEK de cifrado durante un tiempo y un número máximo de usos.</li>
 *   <li>Guarda en caché las DEK ya desenvueltas para lecturas repetidas.</li>
 * </ul>
 * El código cliente no sabe si habla con el proxy o con el KMS directamente.
 */
public class CachingKmsProviderProxy implements KmsProvider {

    private final KmsProvider target;
    private final Clock clock;
    private final Duration dataKeyReuse;
    private final long maxUsesPerDataKey;
    private final Cache<String, byte[]> decryptedKeys;

    private DataKey currentKey;
    private Instant currentKeyCreatedAt;
    private long currentKeyUses;

    public CachingKmsProviderProxy(KmsProvider target, Clock clock, Duration dataKeyReuse,
                                   long maxUsesPerDataKey, Duration decryptedKeyTtl) {
        this.target = target;
        this.clock = clock;
        this.dataKeyReuse = dataKeyReuse;
        this.maxUsesPerDataKey = maxUsesPerDataKey;
        this.decryptedKeys = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(decryptedKeyTtl)
                .build();
    }

    @Override
    public String activeKeyId() {
        return target.activeKeyId();
    }

    @Override
    public synchronized DataKey generateDataKey() {
        Instant now = clock.instant();
        boolean expired = currentKey == null
                || now.isAfter(currentKeyCreatedAt.plus(dataKeyReuse))
                || currentKeyUses >= maxUsesPerDataKey
                || !currentKey.keyId().equals(target.activeKeyId());
        if (expired) {
            currentKey = target.generateDataKey();
            currentKeyCreatedAt = now;
            currentKeyUses = 0;
            decryptedKeys.put(cacheKey(currentKey.keyId(), currentKey.wrapped()), currentKey.plaintext());
        }
        currentKeyUses++;
        return currentKey;
    }

    @Override
    public byte[] decryptDataKey(String keyId, byte[] wrappedKey) {
        byte[] key = decryptedKeys.get(cacheKey(keyId, wrappedKey), k -> target.decryptDataKey(keyId, wrappedKey));
        return key.clone();
    }

    private static String cacheKey(String keyId, byte[] wrapped) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(wrapped);
            return keyId + ":" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
