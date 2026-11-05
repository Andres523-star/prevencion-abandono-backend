package co.edu.retencion.notifications.api;

import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import co.edu.retencion.audit.Audited;
import co.edu.retencion.audit.SensitiveRead;
import co.edu.retencion.common.web.CursorPage;
import co.edu.retencion.notifications.application.NotificationService;
import co.edu.retencion.notifications.application.NotificationService.NotificationView;

/** Campanita: listado (resuelve el nombre del estudiante con auditoría), no leídas y lectura. */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Campanita")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    @SensitiveRead(entity = "StudentIdentity", requireReason = false)
    @Operation(summary = "Mis notificaciones (keyset); el nombre del estudiante se resuelve con ABAC y auditoría")
    public CursorPage<NotificationView> mine(@RequestParam(required = false) UUID after,
                                             @RequestParam(required = false) Integer size) {
        return notifications.mine(after, size);
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Contador de no leídas")
    public Map<String, Long> unread() {
        return Map.of("unread", notifications.unread());
    }

    @PostMapping("/{id}/read")
    @Audited(action = "NOTIFICATION_READ", entity = "Notification", entityId = "#id")
    @Operation(summary = "Marca una notificación como leída")
    public ResponseEntity<Void> read(@PathVariable UUID id) {
        notifications.markRead(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    @Audited(action = "NOTIFICATION_READ_ALL", entity = "Notification")
    @Operation(summary = "Marca todas como leídas")
    public Map<String, Integer> readAll() {
        return Map.of("updated", notifications.markAllRead());
    }
}
