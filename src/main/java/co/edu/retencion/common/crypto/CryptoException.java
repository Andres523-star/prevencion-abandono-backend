package co.edu.retencion.common.crypto;

/** Error de cifrado/descifrado. El mensaje nunca incluye el dato ni la clave. */
public class CryptoException extends RuntimeException {

    public CryptoException(String message) {
        super(message);
    }

    public CryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
