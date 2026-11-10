package co.edu.retencion.privacy;

import java.util.UUID;

/** La solicitud del titular fue respondida (aprobada, rechazada o ejecutada). */
public record DataRequestAnswered(UUID requestId, UUID studentId, UUID requesterUserId, String status) {
}
