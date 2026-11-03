package co.edu.retencion.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * "Blind index": HMAC-SHA256 determinista de un valor normalizado. Permite buscar por correo o
 * documento con una igualdad en SQL sin descifrar la columna ni guardar el dato en claro.
 *
 * <p>Se usa una clave propia (no la de cifrado) y un contexto por tipo de dato, de modo que el
 * mismo número en "documento" y en "teléfono" produce índices distintos.
 */
public class BlindIndexer {

    /** Tipos de dato indexables, cada uno con su normalización. */
    public enum Type {
        EMAIL {
            @Override
            String normalize(String value) {
                return value.trim().toLowerCase(Locale.ROOT);
            }
        },
        DOCUMENT {
            @Override
            String normalize(String value) {
                // "1.234.567-8" y "12345678" deben dar el mismo índice.
                return value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
            }
        },
        PHONE {
            @Override
            String normalize(String value) {
                return value.replaceAll("[^0-9]", "");
            }
        };

        abstract String normalize(String value);
    }

    private final SecretKeySpec key;

    public BlindIndexer(byte[] key) {
        if (key == null || key.length < 32) {
            throw new CryptoException("La clave del blind index debe tener al menos 256 bits");
        }
        this.key = new SecretKeySpec(key, "HmacSHA256");
    }

    /** Índice en hexadecimal (64 caracteres). */
    public String index(Type type, String value) {
        if (value == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            mac.update(type.name().getBytes(StandardCharsets.UTF_8));
            mac.update((byte) ':');
            return HexFormat.of().formatHex(mac.doFinal(type.normalize(value).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new CryptoException("No se pudo calcular el blind index", ex);
        }
    }
}
