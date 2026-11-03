package co.edu.retencion.common.crypto;

/**
 * Puerto hacia el servicio de gestión de claves (KMS). Implementa el cifrado envolvente:
 * la clave maestra nunca sale del KMS; la app solo recibe claves de datos (DEK) y las guarda
 * envueltas (cifradas) junto a cada valor.
 */
public interface KmsProvider {

    /** Id de la clave maestra activa con la que se envuelven las nuevas DEK. */
    String activeKeyId();

    /** Genera una DEK AES-256 nueva: en claro (para cifrar) y envuelta (para guardar). */
    DataKey generateDataKey();

    /** Desenvuelve una DEK guardada usando la clave maestra {@code keyId}. */
    byte[] decryptDataKey(String keyId, byte[] wrappedKey);
}
