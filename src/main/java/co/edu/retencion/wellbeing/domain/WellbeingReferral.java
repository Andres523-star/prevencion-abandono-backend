package co.edu.retencion.wellbeing.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import co.edu.retencion.common.crypto.EncryptedStringConverter;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.id.UuidV7;

/** Remisión a un servicio de bienestar. El motivo se cifra en columna. */
@Entity
@Table(name = "wellbeing_referrals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WellbeingReferral {

    public enum Service {
        PSICOLOGIA,
        MEDICINA,
        TRABAJO_SOCIAL,
        NUTRICION,
        EXTERNA
    }

    /** ABIERTA → ATENDIDA → CERRADA (o ABIERTA → CERRADA). */
    public enum Status {
        ABIERTA,
        ATENDIDA,
        CERRADA
    }

    @Id
    private UUID id;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "assessment_id")
    private UUID assessmentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Service service;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "reason_ciphertext", nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    public static WellbeingReferral open(UUID studentId, UUID assessmentId, Service service, String reason,
                                         UUID createdBy, Instant now) {
        if (reason == null || reason.isBlank() || reason.length() > 2000) {
            throw DomainException.badRequest("El motivo debe tener entre 1 y 2000 caracteres");
        }
        WellbeingReferral r = new WellbeingReferral();
        r.id = UuidV7.newId();
        r.studentId = studentId;
        r.assessmentId = assessmentId;
        r.service = service;
        r.reason = reason.trim();
        r.status = Status.ABIERTA;
        r.createdBy = createdBy;
        r.createdAt = now;
        return r;
    }

    public void moveTo(Status next, Instant now) {
        boolean valid = switch (status) {
            case ABIERTA -> next == Status.ATENDIDA || next == Status.CERRADA;
            case ATENDIDA -> next == Status.CERRADA;
            case CERRADA -> false;
        };
        if (!valid) {
            throw DomainException.conflict("Transición de remisión no permitida: " + status + " → " + next);
        }
        status = next;
        if (next == Status.CERRADA) {
            closedAt = now;
        }
    }

    @Override
    public String toString() {
        return "WellbeingReferral[id=" + id + ", status=" + status + "]";
    }
}
