package co.edu.retencion.privacy;

import java.util.UUID;

/** Un titular radicó una solicitud sobre sus datos (avisa al personal que las atiende). */
public record DataRequestSubmitted(UUID requestId, UUID studentId, String type) {
}
