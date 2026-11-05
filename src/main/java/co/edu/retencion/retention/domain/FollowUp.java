package co.edu.retencion.retention.domain;

import java.time.Instant;
import java.time.LocalDate;
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

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import co.edu.retencion.common.crypto.EncryptedStringConverter;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.id.UuidV7;

/** Seguimiento programado (7/15/30 días) o contacto de la campaña de reingreso. */
@Entity
@Table(name = "retention_follow_ups")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FollowUp {

    /** Tipo. */
    public enum Kind {
        SEGUIMIENTO,
        REINGRESO
    }

    /** Estado. */
    public enum Status {
        PENDIENTE,
        HECHO,
        CANCELADO
    }

    @Id
    private UUID id;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Column(name = "offset_days", nullable = false)
    private int offsetDays;

    @Column(name = "due_on", nullable = false)
    private LocalDate dueOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "reminded_at")
    private Instant remindedAt;

    @Column(name = "done_at")
    private Instant doneAt;


    public static FollowUp schedule(UUID caseId, Kind kind, int offsetDays, LocalDate dueOn) {
        FollowUp f = new FollowUp();
        f.id = UuidV7.newId();
        f.caseId = caseId;
        f.kind = kind;
        f.offsetDays = offsetDays;
        f.dueOn = dueOn;
        f.status = Status.PENDIENTE;
        return f;
    }

    public void reminded(Instant now) {
        this.remindedAt = now;
    }

    public void done(Instant now) {
        this.status = Status.HECHO;
        this.doneAt = now;
    }

    public void cancel() {
        if (status == Status.PENDIENTE) {
            this.status = Status.CANCELADO;
        }
    }

}
