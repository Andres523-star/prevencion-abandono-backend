package co.edu.retencion.common.crypto;

/**
 * Clave de datos (DEK) generada por el KMS.
 *
 * @param keyId     clave maestra que la envolvió
 * @param plaintext DEK en claro (32 bytes); no debe persistirse ni registrarse
 * @param wrapped   DEK cifrada con la clave maestra; es lo que se guarda
 */
public record DataKey(String keyId, byte[] plaintext, byte[] wrapped) {

    public DataKey {
        plaintext = plaintext.clone();
        wrapped = wrapped.clone();
    }

    @Override
    public byte[] plaintext() {
        return plaintext.clone();
    }

    @Override
    public byte[] wrapped() {
        return wrapped.clone();
    }

    @Override
    public String toString() {
        return "DataKey[keyId=" + keyId + ", plaintext=***]";
    }
}
