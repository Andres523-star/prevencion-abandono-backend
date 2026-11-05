package co.edu.retencion.notifications;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Últimas notificaciones de un usuario para el tablero. */
public interface NotificationFeed {

    List<FeedItem> latest(UUID userId, int limit);

    /** Sin nombre del estudiante: el detalle sigue en la campanita, con su auditoría. */
    record FeedItem(UUID id, String type, UUID studentId, String riskLevel, String message, boolean read, Instant createdAt) {
    }
}
