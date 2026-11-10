package co.edu.retencion.notifications.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import co.edu.retencion.common.id.UuidV7;

/**
 * Notificación interna. No copia el nombre del estudiante: solo su id y un motivo estructurado.
 */
@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    /** Tipo de notificación. */
    public enum Type {
        RIESGO_ESTUDIANTE,
        ALERTA_ASIGNADA,
        ALERTA_ESCALADA,
        JUSTIFICACION_PENDIENTE,
        JUSTIFICACION_DECIDIDA,
        JUSTIFICACION_APELADA,
        NUEVO_MENSAJE,
        INTERVENCION_ASIGNADA,
        SEGUIMIENTO_RETENCION,
        CAMPANA_REINGRESO,
        SOLICITUD_DATOS,
        SOLICITUD_DATOS_RESPONDIDA
    }

    @Id
    private UUID id;

    @Column(name = "recipient_id", nullable = false)
    private UUID recipientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Column(name = "student_id")
    private UUID studentId;

    @Column(name = "risk_level")
    private String riskLevel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String reason;

    @Column(name = "reason_hash", nullable = false)
    private String reasonHash;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public static Notification of(UUID recipient, Type type, UUID studentId, String riskLevel, String reasonJson,
                                  String reasonHash, UUID referenceId, Instant now, Instant expiresAt) {
        Notification n = new Notification();
        n.id = UuidV7.newId();
        n.recipientId = recipient;
        n.type = type;
        n.studentId = studentId;
        n.riskLevel = riskLevel;
        n.reason = reasonJson;
        n.reasonHash = reasonHash;
        n.referenceId = referenceId;
        n.createdAt = now;
        n.expiresAt = expiresAt;
        return n;
    }

    public void markRead(Instant now) {
        if (readAt == null) {
            this.readAt = now;
        }
    }
}
