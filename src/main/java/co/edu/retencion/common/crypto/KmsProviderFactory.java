package co.edu.retencion.common.crypto;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * <b>Patrón creacional: Factory Method.</b> Declara el método de fabricación
 * {@link #create(CryptoProperties)}; cada fábrica concreta decide qué {@link KmsProvider}
 * construir. Para añadir un KMS nuevo (OCI Vault, AWS KMS) basta con otra implementación,
 * sin modificar {@code CryptoConfig}.
 */
public interface KmsProviderFactory {

    /** Valor de {@code app.crypto.kms-type} que atiende esta fábrica. */
    String type();

    KmsProvider create(CryptoProperties properties);

    /** Fábrica concreta del KMS local (claves en variables de entorno). */
    final class Local implements KmsProviderFactory {

        @Override
        public String type() {
            return "local";
        }

        @Override
        public KmsProvider create(CryptoProperties properties) {
            CryptoProperties.Local local = properties.local();
            if (local.masterKey() == null || local.masterKey().isBlank()) {
                throw new CryptoException("Falta APP_CRYPTO_MASTER_KEY para el KMS local");
            }
            Map<String, byte[]> keys = new HashMap<>();
            keys.put(local.activeKeyId(), Base64.getDecoder().decode(local.masterKey().trim()));
            for (String entry : local.previousKeys()) {
                int sep = entry.indexOf(':');
                if (sep <= 0) {
                    throw new CryptoException("Formato de clave anterior inválido (se espera id:base64)");
                }
                keys.put(entry.substring(0, sep), Base64.getDecoder().decode(entry.substring(sep + 1).trim()));
            }
            return new LocalKmsProvider(local.activeKeyId(), keys);
        }
    }

    /** Fábrica concreta del adaptador de Vault Transit. */
    final class Vault implements KmsProviderFactory {

        @Override
        public String type() {
            return "vault";
        }

        @Override
        public KmsProvider create(CryptoProperties properties) {
            CryptoProperties.Vault vault = properties.vault();
            if (vault.uri() == null || vault.token() == null) {
                throw new CryptoException("Faltan VAULT_URI y VAULT_TOKEN para el KMS de Vault");
            }
            return new VaultTransitKmsAdapter(vault.uri(), vault.token(), vault.keyName());
        }
    }
}
