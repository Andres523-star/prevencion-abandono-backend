package co.edu.retencion.common.crypto;

import java.time.Clock;
import java.util.Base64;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Ensambla la infraestructura de cifrado:
 * fábrica (Factory Method) → KMS real (local o adaptador de Vault) → proxy de caché → encryptor.
 */
@Configuration(proxyBeanMethods = false)
public class CryptoConfig {

    private static final List<KmsProviderFactory> FACTORIES = List.of(
            new KmsProviderFactory.Local(),
            new KmsProviderFactory.Vault());

    @Bean
    KmsProvider kmsProvider(CryptoProperties properties, Clock clock) {
        KmsProviderFactory factory = FACTORIES.stream()
                .filter(f -> f.type().equalsIgnoreCase(properties.kmsType()))
                .findFirst()
                .orElseThrow(() -> new CryptoException("Tipo de KMS no soportado: " + properties.kmsType()));
        CryptoProperties.Cache cache = properties.cache();
        return new CachingKmsProviderProxy(factory.create(properties), clock,
                cache.dataKeyReuse(), cache.maxUsesPerDataKey(), cache.decryptedKeyTtl());
    }

    @Bean
    EnvelopeEncryptor envelopeEncryptor(KmsProvider kmsProvider) {
        return new EnvelopeEncryptor(kmsProvider);
    }

    @Bean
    BlindIndexer blindIndexer(CryptoProperties properties) {
        if (properties.blindIndexKey() == null || properties.blindIndexKey().isBlank()) {
            throw new CryptoException("Falta APP_CRYPTO_BLIND_INDEX_KEY");
        }
        return new BlindIndexer(Base64.getDecoder().decode(properties.blindIndexKey().trim()));
    }
}
