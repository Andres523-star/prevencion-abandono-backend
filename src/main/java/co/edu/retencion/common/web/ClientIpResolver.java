package co.edu.retencion.common.web;

import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Obtiene la IP real del cliente detrás de proxies (Render, balanceadores) sin confiar
 * ciegamente en cabeceras que el cliente puede falsificar.
 *
 * <p>Estrategia configurable:
 * <ul>
 *   <li>{@code app.http.client-ip-header}: cabecera fijada por un proxy de confianza
 *       (p. ej. {@code True-Client-IP}). Tiene prioridad si está configurada.</li>
 *   <li>{@code app.http.trusted-proxy-hops}: cuántos proxies de confianza agregan entradas al
 *       final de {@code X-Forwarded-For}. Se toma la entrada en esa posición contando desde la
 *       derecha; lo que esté más a la izquierda lo pudo escribir el cliente.</li>
 * </ul>
 */
@Component
public class ClientIpResolver {

    private static final Pattern IP_CHARS = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");

    private final HttpProperties properties;

    public ClientIpResolver(HttpProperties properties) {
        this.properties = properties;
    }

    public String resolve(HttpServletRequest request) {
        if (StringUtils.hasText(properties.clientIpHeader())) {
            String value = request.getHeader(properties.clientIpHeader());
            if (isIp(value)) {
                return value.trim();
            }
        }
        int hops = properties.trustedProxyHops();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (hops > 0 && StringUtils.hasText(forwarded)) {
            String[] parts = forwarded.split(",");
            int index = parts.length - hops;
            if (index >= 0 && isIp(parts[index])) {
                return parts[index].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private static boolean isIp(String value) {
        return value != null && IP_CHARS.matcher(value.trim()).matches();
    }
}
