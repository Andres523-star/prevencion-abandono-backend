package co.edu.retencion.common.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Propiedades HTTP transversales ({@code app.http.*}).
 *
 * @param trustedProxyHops número de proxies de confianza delante de la app (Render = 1)
 * @param clientIpHeader   cabecera opcional con la IP del cliente puesta por el proxy
 */
@ConfigurationProperties("app.http")
public record HttpProperties(
        @DefaultValue("0") int trustedProxyHops,
        String clientIpHeader) {
}
