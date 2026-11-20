package co.edu.permanencia.demo.chat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

/**
 * Firma de enlaces de descarga de adjuntos con vencimiento (HMAC-SHA256).
 *
 * <p>Un {@code <audio>} o un enlace de descarga no pueden enviar la cabecera {@code Authorization},
 * así que la descarga no pasa por el interceptor de sesión. En lugar de dejarla abierta para
 * siempre, cada mensaje lleva una firma que solo sirve para ese archivo y vence a la hora
 * (como las URL prefirmadas de 5 minutos del backend principal). Quien puede leer la conversación
 * recibe la firma; quien no, no puede descargar aunque conozca el id.</p>
 */
@Component
class EnlacesAdjuntos {

    private final byte[] clave;
    private final Clock clock;
    private final Duration vigencia;

    @Autowired
    EnlacesAdjuntos(@Value("${demo.chat.link-secret:}") String secreto,
                    @Value("${demo.chat.link-minutes:60}") long minutos) {
        this(claveDe(secreto), Clock.systemUTC(), Duration.ofMinutes(minutos));
    }

    EnlacesAdjuntos(byte[] clave, Clock clock, Duration vigencia) {
        this.clave = clave.clone();
        this.clock = clock;
        this.vigencia = vigencia;
    }

    /**
     * Firma con el formato {@code <vence-en-segundos>.<hmac>}.
     *
     * <p>El vencimiento se redondea al final del siguiente tramo de {@code vigencia}: dentro de un
     * mismo tramo la firma (y por tanto la URL) no cambia. Si cambiara en cada recarga del chat,
     * el {@code <audio>} de una nota de voz se reiniciaría mientras suena. Así un enlace dura entre
     * una y dos veces la vigencia.</p>
     */
    String firmar(String archivoId) {
        long tramo = Math.max(60, vigencia.toSeconds());
        long vence = (clock.instant().getEpochSecond() / tramo + 2) * tramo;
        return vence + "." + hmac(archivoId + "|" + vence);
    }

    /** True si la firma corresponde al archivo y no ha vencido. */
    boolean valida(String archivoId, String firma) {
        if (archivoId == null || firma == null || firma.length() > 200) {
            return false;
        }
        int punto = firma.indexOf('.');
        if (punto <= 0) {
            return false;
        }
        long vence;
        try {
            vence = Long.parseLong(firma.substring(0, punto));
        } catch (NumberFormatException ex) {
            return false;
        }
        if (clock.instant().getEpochSecond() > vence) {
            return false;
        }
        byte[] esperado = hmac(archivoId + "|" + vence).getBytes(StandardCharsets.US_ASCII);
        byte[] recibido = firma.substring(punto + 1).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(esperado, recibido);
    }

    private String hmac(String datos) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(clave, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(datos.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 no disponible", ex);
        }
    }

    /** Sin secreto configurado se genera uno al arrancar (los enlaces mueren al reiniciar, igual que los adjuntos en memoria). */
    private static byte[] claveDe(String secreto) {
        if (secreto != null && secreto.length() >= 32) {
            return secreto.getBytes(StandardCharsets.UTF_8);
        }
        byte[] aleatoria = new byte[32];
        new SecureRandom().nextBytes(aleatoria);
        return aleatoria;
    }
}
