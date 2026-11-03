package co.edu.retencion.common.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * KMS local para desarrollo, tests y pilotos pequeños: las claves maestras llegan por variable
 * de entorno. Soporta rotación: la clave activa envuelve las DEK nuevas y las anteriores siguen
 * disponibles para descifrar datos antiguos.
 *
 * <p>En producción con datos reales se recomienda {@link VaultTransitKmsAdapter} o el KMS del
 * proveedor de nube, para que la clave maestra no viva en la memoria de la app.
 */
public class LocalKmsProvider implements KmsProvider {

    private final String activeKeyId;
    private final Map<String, byte[]> masterKeys;

    public LocalKmsProvider(String activeKeyId, Map<String, byte[]> masterKeys) {
        if (!masterKeys.containsKey(activeKeyId)) {
            throw new CryptoException("La clave maestra activa no está configurada");
        }
        masterKeys.values().forEach(k -> {
            if (k.length != AesGcm.KEY_BYTES) {
                throw new CryptoException("Cada clave maestra debe ser de 256 bits");
            }
        });
        this.activeKeyId = activeKeyId;
        this.masterKeys = Map.copyOf(masterKeys);
    }

    @Override
    public String activeKeyId() {
        return activeKeyId;
    }

    @Override
    public DataKey generateDataKey() {
        byte[] dek = AesGcm.randomKey();
        byte[] wrapped = AesGcm.encrypt(masterKeys.get(activeKeyId), dek, aad(activeKeyId));
        return new DataKey(activeKeyId, dek, wrapped);
    }

    @Override
    public byte[] decryptDataKey(String keyId, byte[] wrappedKey) {
        byte[] master = masterKeys.get(keyId);
        if (master == null) {
            throw new CryptoException("Clave maestra desconocida: " + keyId);
        }
        return AesGcm.decrypt(master, wrappedKey, aad(keyId));
    }

    private static byte[] aad(String keyId) {
        return ("dek:" + keyId).getBytes(StandardCharsets.UTF_8);
    }
}
