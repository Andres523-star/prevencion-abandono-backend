package co.edu.retencion.privacy;

import java.util.UUID;

/**
 * Se suprimieron los datos personales de un estudiante. Cada módulo borra lo suyo (chat, campanita).
 *
 * @param userId usuario que estaba vinculado, o {@code null}
 */
public record StudentDataSuppressed(UUID studentId, UUID userId) {
}
