package co.edu.retencion.notifications.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.AuthenticatedUser;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.common.security.authorization.AccessAction;
import co.edu.retencion.common.security.authorization.ScopeAuthorizationService;
import co.edu.retencion.common.web.CursorPage;
import co.edu.retencion.notifications.domain.Notification;
import co.edu.retencion.notifications.domain.NotificationRepository;
import co.edu.retencion.notifications.domain.ReasonText;
import co.edu.retencion.realtime.RealtimePublisher;
import co.edu.retencion.settings.SettingsProvider;
import co.edu.retencion.students.StudentRegistry;
import co.edu.retencion.users.UserDirectory;

/**
 * Crea (con deduplicación), entrega en tiempo real y lista las notificaciones. La entrega en tiempo
 * real no incluye el nombre del estudiante: el cliente lo obtiene al listar (lectura auditada).
 */
@Service
public class NotificationService implements co.edu.retencion.notifications.NotificationFeed {

    private final NotificationRepository notifications;
    private final RealtimePublisher realtime;
    private final StudentRegistry students;
    private final UserDirectory users;
    private final ScopeAuthorizationService scope;
    private final SettingsProvider settings;
    private final ObjectMapper mapper;
    private final Clock clock;

    public NotificationService(NotificationRepository notifications, RealtimePublisher realtime,
                               StudentRegistry students, UserDirectory users, ScopeAuthorizationService scope,
                               SettingsProvider settings, ObjectMapper mapper, Clock clock) {
        this.notifications = notifications;
        this.realtime = realtime;
        this.students = students;
        this.users = users;
        this.scope = scope;
        this.settings = settings;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Acción "Abrir chat" (petición que el cliente debe hacer). */
    public record OpenChatAction(String method, String path, UUID studentId) {
    }

    /**
     * Vista de la campanita.
     *
     * @param studentName     nombre (solo si quien lee tiene alcance sobre el estudiante; lectura auditada)
     * @param reason          motivo estructurado (códigos y valores)
     * @param message         motivo legible por plantilla
     * @param openChat        acción "Abrir chat" (docente)
     * @param appointmentLink acción "Copiar enlace de cita" (enlace del propio docente)
     */
    public record NotificationView(UUID id, Notification.Type type, UUID studentId, String studentName,
                                   String riskLevel, Map<String, Object> reason, String message, UUID referenceId,
                                   boolean read, Instant readAt, Instant createdAt, OpenChatAction openChat,
                                   String appointmentLink) {

        @Override
        public String toString() {
            return "NotificationView[" + id + "]";
        }
    }

    /** Lo que viaja por WebSocket (sin nombre). */
    public record PushView(UUID id, Notification.Type type, UUID studentId, String riskLevel, String message,
                           UUID referenceId, Instant createdAt) {
    }

    /** Crea la notificación salvo que sea un duplicado reciente; la entrega tras confirmar. */
    @Transactional
    public boolean notify(UUID recipient, Notification.Type type, UUID studentId, String riskLevel,
                          Map<String, Object> reason, UUID referenceId) {
        if (recipient == null) {
            return false;
        }
        Instant now = clock.instant();
        String json = json(new TreeMap<>(reason));
        String hash = sha256(json);
        if (type == Notification.Type.NUEVO_MENSAJE) {
            if (notifications.existsByRecipientIdAndTypeAndReferenceIdAndReadAtIsNull(recipient, type, referenceId)) {
                return false;
            }
        } else if (studentId != null && notifications.duplicate(recipient, type, studentId, hash, riskLevel,
                now.minus(Duration.ofHours(Math.max(1, settings.getInt("notifications.dedupe-hours", 72)))))) {
            return false;
        }
        Notification n = notifications.save(Notification.of(recipient, type, studentId, riskLevel, json, hash,
                referenceId, now, now.plus(Duration.ofDays(Math.max(1, settings.getInt("notifications.expiry-days", 30))))));
        PushView push = new PushView(n.getId(), type, studentId, riskLevel, ReasonText.of(type, reason), referenceId, now);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    realtime.toUser(recipient, "notifications", push);
                }
            });
        } else {
            realtime.toUser(recipient, "notifications", push);
        }
        return true;
    }

    @Transactional(readOnly = true)
    public CursorPage<NotificationView> mine(UUID after, Integer size) {
        AuthenticatedUser user = CurrentUser.require();
        int limit = CursorPage.clampSize(size);
        String link = user.hasRole(Role.DOCENTE) ? users.appointmentLink(user.userId()).orElse(null) : null;
        List<NotificationView> rows = notifications.page(user.userId(), clock.instant(), after == null, after,
                Limit.of(limit + 1)).stream().map(n -> view(n, user, link)).toList();
        return CursorPage.of(rows, limit, NotificationView::id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FeedItem> latest(UUID userId, int limit) {
        return notifications.page(userId, clock.instant(), true, null, Limit.of(limit)).stream()
                .map(n -> new FeedItem(n.getId(), n.getType().name(), n.getStudentId(), n.getRiskLevel(),
                        ReasonText.of(n.getType(), read(n.getReason())), n.getReadAt() != null, n.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public long unread() {
        return notifications.unread(CurrentUser.require().userId(), clock.instant());
    }

    @Transactional
    public void markRead(UUID id) {
        Notification n = notifications.findById(id).filter(x -> x.getRecipientId().equals(CurrentUser.require().userId()))
                .orElseThrow(() -> DomainException.notFound("Notificación no encontrada"));
        n.markRead(clock.instant());
        notifications.save(n);
    }

    @Transactional
    public int markAllRead() {
        return notifications.readAll(CurrentUser.require().userId(), clock.instant());
    }

    @Scheduled(cron = "0 45 3 * * *", zone = "America/Bogota")
    @Transactional
    public int purgeExpired() {
        return notifications.purgeExpired(clock.instant());
    }

    private NotificationView view(Notification n, AuthenticatedUser viewer, String appointmentLink) {
        Map<String, Object> reason = read(n.getReason());
        String name = null;
        boolean staffType = n.getType() != Notification.Type.JUSTIFICACION_DECIDIDA;
        if (n.getStudentId() != null && staffType && scope.canAccess("STUDENT", n.getStudentId(), AccessAction.READ)) {
            name = students.displayName(n.getStudentId()).orElse(null);
        }
        boolean teacherAction = viewer.hasRole(Role.DOCENTE) && n.getStudentId() != null
                && (n.getType() == Notification.Type.RIESGO_ESTUDIANTE
                || n.getType() == Notification.Type.JUSTIFICACION_PENDIENTE);
        return new NotificationView(n.getId(), n.getType(), n.getStudentId(), name, n.getRiskLevel(), reason,
                ReasonText.of(n.getType(), reason), n.getReferenceId(), n.getReadAt() != null, n.getReadAt(),
                n.getCreatedAt(), teacherAction ? new OpenChatAction("POST", "/api/v1/chats", n.getStudentId()) : null,
                teacherAction ? appointmentLink : null);
    }

    private Map<String, Object> read(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
