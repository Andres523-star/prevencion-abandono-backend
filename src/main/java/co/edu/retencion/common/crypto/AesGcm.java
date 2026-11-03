package co.edu.retencion.common.crypto;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Primitiva AES-256-GCM (cifrado autenticado). IV aleatorio de 96 bits y etiqueta de 128 bits.
 * Salida: {@code iv || ciphertext+tag}.
 */
final class AesGcm {

    static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private AesGcm() {
    }

    static byte[] encrypt(byte[] key, byte[] plaintext, byte[] aad) {
        requireKey(key);
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            if (aad != null) {
                cipher.updateAAD(aad);
            }
            byte[] ciphertext = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(IV_BYTES + ciphertext.length).put(iv).put(ciphertext).array();
        } catch (GeneralSecurityException ex) {
            throw new CryptoException("No se pudo cifrar", ex);
        }
    }

    static byte[] decrypt(byte[] key, byte[] ivAndCiphertext, byte[] aad) {
        requireKey(key);
        if (ivAndCiphertext.length < IV_BYTES + TAG_BITS / 8) {
            throw new CryptoException("Texto cifrado demasiado corto");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, ivAndCiphertext, 0, IV_BYTES));
            if (aad != null) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(ivAndCiphertext, IV_BYTES, ivAndCiphertext.length - IV_BYTES);
        } catch (GeneralSecurityException ex) {
            // Incluye AEADBadTagException: el dato fue alterado o la clave no corresponde.
            throw new CryptoException("No se pudo descifrar (dato alterado o clave incorrecta)", ex);
        }
    }

    static byte[] randomKey() {
        byte[] key = new byte[KEY_BYTES];
        RANDOM.nextBytes(key);
        return key;
    }

    private static void requireKey(byte[] key) {
        if (key == null || key.length != KEY_BYTES) {
            throw new CryptoException("Se requiere una clave AES-256");
        }
    }
}
