package co.edu.retencion.common.security;

import java.util.UUID;

/**
 * Evento: una sesión de la plataforma dejó de ser válida (logout, reutilización de refresh token,
 * cuenta desactivada o MFA exigido). El módulo de tiempo real cierra las conexiones WebSocket de
 * esa sesión sin esperar a que venza el access token.
 *
 * @param userId    usuario
 * @param sessionId familia de la sesión, o {@code null} si se revocan todas las del usuario
 */
public record SessionRevoked(UUID userId, UUID sessionId) {
}
