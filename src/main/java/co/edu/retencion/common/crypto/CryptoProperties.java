package co.edu.retencion.common.crypto;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración de cifrado ({@code app.crypto.*}). Todas las claves vienen de variables de entorno.
 *
 * @param kmsType        {@code local} o {@code vault}
 * @param blindIndexKey  clave HMAC (Base64, 256 bits) para los blind index; distinta de las de cifrado
 * @param local          claves maestras para el KMS local
 * @param vault          conexión a Vault Transit
 * @param cache          parámetros del proxy de caché del KMS
 */
@ConfigurationProperties("app.crypto")
public record CryptoProperties(
        @DefaultValue("local") String kmsType,
        String blindIndexKey,
        @DefaultValue Local local,
        @DefaultValue Vault vault,
        @DefaultValue Cache cache) {

    /**
     * @param activeKeyId  id de la clave maestra activa
     * @param masterKey    clave maestra activa en Base64 (256 bits)
     * @param previousKeys claves retiradas, formato {@code id:base64}, solo para descifrar
     */
    public record Local(@DefaultValue("local-v1") String activeKeyId, String masterKey,
                        @DefaultValue List<String> previousKeys) {
    }

    public record Vault(String uri, String token, @DefaultValue("retencion-pii") String keyName) {
    }

    public record Cache(@DefaultValue("10m") Duration dataKeyReuse,
                        @DefaultValue("10000") long maxUsesPerDataKey,
                        @DefaultValue("30m") Duration decryptedKeyTtl) {
    }
}
