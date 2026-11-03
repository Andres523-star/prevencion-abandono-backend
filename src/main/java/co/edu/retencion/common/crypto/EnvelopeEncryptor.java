package co.edu.retencion.common.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Cifrado envolvente de valores de columna con AES-256-GCM.
 *
 * <p>Formato guardado en BD: {@code "v1." + Base64(}
 * {@code [1 B versión][1 B largo keyId][keyId][2 B largo DEK envuelta][DEK envuelta][iv+ciphertext+tag])}.
 * La cabecera (versión, keyId y DEK envuelta) se autentica como AAD, así que alterarla hace
 * fallar el descifrado.
 */
public class EnvelopeEncryptor {

    private static final String PREFIX = "v1.";
    private static final byte VERSION = 1;

    private final KmsProvider kms;

    public EnvelopeEncryptor(KmsProvider kms) {
        this.kms = kms;
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        DataKey dataKey = kms.generateDataKey();
        byte[] keyId = dataKey.keyId().getBytes(StandardCharsets.UTF_8);
        byte[] wrapped = dataKey.wrapped();
        byte[] header = header(keyId, wrapped);
        byte[] body = AesGcm.encrypt(dataKey.plaintext(), plaintext.getBytes(StandardCharsets.UTF_8), header);
        byte[] out = ByteBuffer.allocate(header.length + body.length).put(header).put(body).array();
        return PREFIX + Base64.getEncoder().encodeToString(out);
    }

    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        if (!stored.startsWith(PREFIX)) {
            throw new CryptoException("Formato de cifrado desconocido");
        }
        ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(stored.substring(PREFIX.length())));
        try {
            byte version = buffer.get();
            if (version != VERSION) {
                throw new CryptoException("Versión de cifrado no soportada");
            }
            byte[] keyId = new byte[Byte.toUnsignedInt(buffer.get())];
            buffer.get(keyId);
            byte[] wrapped = new byte[Short.toUnsignedInt(buffer.getShort())];
            buffer.get(wrapped);
            byte[] body = new byte[buffer.remaining()];
            buffer.get(body);
            byte[] dek = kms.decryptDataKey(new String(keyId, StandardCharsets.UTF_8), wrapped);
            return new String(AesGcm.decrypt(dek, body, header(keyId, wrapped)), StandardCharsets.UTF_8);
        } catch (java.nio.BufferUnderflowException ex) {
            throw new CryptoException("Texto cifrado truncado", ex);
        }
    }

    private static byte[] header(byte[] keyId, byte[] wrapped) {
        if (keyId.length > 255 || wrapped.length > 65_535) {
            throw new CryptoException("Cabecera de cifrado demasiado grande");
        }
        return ByteBuffer.allocate(1 + 1 + keyId.length + 2 + wrapped.length)
                .put(VERSION)
                .put((byte) keyId.length)
                .put(keyId)
                .putShort((short) wrapped.length)
                .put(wrapped)
                .array();
    }
}
