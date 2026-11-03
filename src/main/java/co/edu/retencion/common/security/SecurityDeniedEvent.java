package co.edu.retencion.common.security;

import java.util.UUID;

/**
 * Evento publicado cuando se niega un acceso (403). El módulo de auditoría lo escucha y lo
 * registra; así common no depende de audit.
 *
 * @param userId usuario que intentó el acceso (puede ser {@code null})
 * @param method método HTTP
 * @param path   ruta solicitada (sin query string, que podría contener datos)
 * @param ip     IP del cliente
 */
public record SecurityDeniedEvent(UUID userId, String method, String path, String ip) {
}
