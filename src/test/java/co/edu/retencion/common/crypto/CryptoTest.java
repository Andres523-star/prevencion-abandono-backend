package co.edu.retencion.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Cifrado envolvente AES-256-GCM, rotación de claves, detección de manipulación, proxy de
 * caché del KMS, fábricas (Factory Method) y blind index.
 */
class CryptoTest {

    private static byte[] key() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return k;
    }

    @Test
    void encryptsAndDecryptsWithRandomIv() {
        EnvelopeEncryptor encryptor = new EnvelopeEncryptor(new LocalKmsProvider("k1", Map.of("k1", key())));
        String a = encryptor.encrypt("persona@universidad.edu.co");
        String b = encryptor.encrypt("persona@universidad.edu.co");

        assertThat(a).startsWith("v1.").isNotEqualTo(b).doesNotContain("persona");
        assertThat(encryptor.decrypt(a)).isEqualTo("persona@universidad.edu.co");
        assertThat(encryptor.encrypt(null)).isNull();
        assertThat(encryptor.decrypt(null)).isNull();
    }

    @Test
    void detectsTampering() {
        EnvelopeEncryptor encryptor = new EnvelopeEncryptor(new LocalKmsProvider("k1", Map.of("k1", key())));
        byte[] raw = Base64.getDecoder().decode(encryptor.encrypt("dato sensible").substring(3));
        raw[raw.length - 1] ^= 0x01;
        String tampered = "v1." + Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> encryptor.decrypt(tampered)).isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> encryptor.decrypt("texto-plano")).isInstanceOf(CryptoException.class);
    }

    @Test
    void oldDataRemainsReadableAfterKeyRotation() {
        byte[] oldKey = key();
        String stored = new EnvelopeEncryptor(new LocalKmsProvider("k1", Map.of("k1", oldKey))).encrypt("histórico");
        EnvelopeEncryptor rotated = new EnvelopeEncryptor(new LocalKmsProvider("k2", Map.of("k2", key(), "k1", oldKey)));
        assertThat(rotated.decrypt(stored)).isEqualTo("histórico");
        assertThat(rotated.encrypt("nuevo")).isNotNull();
    }

    @Test
    void cachingProxyAvoidsRepeatedKmsCalls() {
        KmsProvider real = spy(new LocalKmsProvider("k1", Map.of("k1", key())));
        CachingKmsProviderProxy proxy = new CachingKmsProviderProxy(real, Clock.systemUTC(), Duration.ofMinutes(5),
                1_000, Duration.ofMinutes(5));
        EnvelopeEncryptor encryptor = new EnvelopeEncryptor(proxy);
        String a = encryptor.encrypt("a");
        String b = encryptor.encrypt("b");
        encryptor.decrypt(a);
        encryptor.decrypt(b);

        verify(real, times(1)).generateDataKey();
        verify(real, times(0)).decryptDataKey(anyString(), any());
    }

    @Test
    void cachingProxyRotatesDataKeyAfterMaxUses() {
        KmsProvider real = spy(new LocalKmsProvider("k1", Map.of("k1", key())));
        CachingKmsProviderProxy proxy = new CachingKmsProviderProxy(real, Clock.systemUTC(), Duration.ofMinutes(5),
                2, Duration.ofMinutes(5));
        proxy.generateDataKey();
        proxy.generateDataKey();
        proxy.generateDataKey();
        verify(real, times(2)).generateDataKey();
    }

    @Test
    void factoriesBuildConfiguredProviders() {
        String master = Base64.getEncoder().encodeToString(key());
        CryptoProperties props = new CryptoProperties("local", null,
                new CryptoProperties.Local("k1", master, List.of("k0:" + Base64.getEncoder().encodeToString(key()))),
                new CryptoProperties.Vault(null, null, "x"),
                new CryptoProperties.Cache(Duration.ofMinutes(1), 10, Duration.ofMinutes(1)));
        KmsProvider local = new KmsProviderFactory.Local().create(props);
        assertThat(local.activeKeyId()).isEqualTo("k1");

        assertThatThrownBy(() -> new KmsProviderFactory.Vault().create(props)).isInstanceOf(CryptoException.class);
        KmsProvider vault = new KmsProviderFactory.Vault().create(new CryptoProperties("vault", null, props.local(),
                new CryptoProperties.Vault("http://localhost:8200", "token", "pii"), props.cache()));
        assertThat(vault).isInstanceOf(VaultTransitKmsAdapter.class);
        assertThat(vault.activeKeyId()).isEqualTo("pii");
    }

    @Test
    void localKmsRejectsWeakKeys() {
        assertThatThrownBy(() -> new LocalKmsProvider("k1", Map.of("k1", new byte[16]))).isInstanceOf(CryptoException.class);
        assertThatThrownBy(() -> new LocalKmsProvider("k9", Map.of("k1", key()))).isInstanceOf(CryptoException.class);
    }

    @Test
    void blindIndexIsDeterministicNormalizedAndContextual() {
        BlindIndexer indexer = new BlindIndexer(key());
        assertThat(indexer.index(BlindIndexer.Type.EMAIL, " Persona@Universidad.edu.co "))
                .isEqualTo(indexer.index(BlindIndexer.Type.EMAIL, "persona@universidad.edu.co"))
                .hasSize(64);
        assertThat(indexer.index(BlindIndexer.Type.DOCUMENT, "1.234.567-8"))
                .isEqualTo(indexer.index(BlindIndexer.Type.DOCUMENT, "12345678"));
        assertThat(indexer.index(BlindIndexer.Type.DOCUMENT, "3001234567"))
                .isNotEqualTo(indexer.index(BlindIndexer.Type.PHONE, "3001234567"));
        assertThat(new BlindIndexer(key()).index(BlindIndexer.Type.EMAIL, "a@b.co"))
                .isNotEqualTo(indexer.index(BlindIndexer.Type.EMAIL, "a@b.co"));
        assertThat(indexer.index(BlindIndexer.Type.EMAIL, null)).isNull();
        assertThatThrownBy(() -> new BlindIndexer(new byte[8])).isInstanceOf(CryptoException.class);
    }
}
