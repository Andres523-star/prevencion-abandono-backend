package co.edu.retencion.users.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

import co.edu.retencion.common.error.DomainException;

/**
 * Validación estricta del enlace de citas del docente (servicio de dominio puro): solo {@code https},
 * sin credenciales embebidas ni puerto, y con un host de la lista blanca configurable (o un
 * subdominio de ella). Rechaza {@code javascript:}, {@code data:}, {@code http:} y cualquier otro
 * esquema o dominio: el enlace se muestra al estudiante, así que no puede llevarlo a un sitio
 * arbitrario.
 */
public final class AppointmentLinkPolicy {

    static final int MAX_LENGTH = 500;

    private AppointmentLinkPolicy() {
    }

    /** Devuelve el enlace normalizado o lanza 400 con el motivo. */
    public static String validate(String raw, List<String> allowedDomains) {
        if (raw == null || raw.isBlank()) {
            throw DomainException.badRequest("El enlace es obligatorio");
        }
        String link = raw.trim();
        if (link.length() > MAX_LENGTH || link.chars().anyMatch(c -> c < 0x21 || c == 0x7F)) {
            throw DomainException.badRequest("El enlace no es válido");
        }
        URI uri;
        try {
            uri = new URI(link);
        } catch (URISyntaxException ex) {
            throw DomainException.badRequest("El enlace no es una URL válida");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw DomainException.badRequest("El enlace debe usar https");
        }
        if (uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getHost() == null) {
            throw DomainException.badRequest("El enlace no puede incluir usuario, clave ni puerto");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean allowed = allowedDomains.stream().map(d -> d.toLowerCase(Locale.ROOT).trim())
                .anyMatch(d -> !d.isEmpty() && (host.equals(d) || host.endsWith("." + d)));
        if (!allowed) {
            throw DomainException.badRequest("El dominio " + host + " no está permitido para enlaces de citas");
        }
        return link;
    }
}
