package co.edu.retencion.auth.application;

import java.util.UUID;

/**
 * Par de tokens devuelto al iniciar o renovar sesión.
 *
 * @param accessToken      JWT de acceso (Bearer)
 * @param tokenType        siempre {@code Bearer}
 * @param expiresIn        segundos de vida del token de acceso
 * @param refreshToken     token opaco de renovación (de un solo uso)
 * @param refreshExpiresIn segundos de vida del refresh token
 * @param userId           id interno del usuario (para la auditoría y el frontend)
 */
public record SessionTokens(String accessToken, String tokenType, long expiresIn, String refreshToken,
                            long refreshExpiresIn, UUID userId) {

    /** Evita que los tokens terminen en un log por accidente. */
    @Override
    public String toString() {
        return "SessionTokens[userId=" + userId + ", expiresIn=" + expiresIn + "]";
    }
}
