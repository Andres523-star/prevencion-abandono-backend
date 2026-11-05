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

/** Campaña de reingreso: borrador que solo genera contactos cuando un responsable la aprueba. */
@Entity
@Table(name = "retention_campaigns")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReentryCampaign {

    /** Estado. */
    public enum Status {
        BORRADOR,
        APROBADA,
        DESCARTADA
    }

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String targets;

    @Column(name = "target_count", nullable = false)
    private int targetCount;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;


    public static ReentryCampaign draft(String targetsJson, int count, UUID actor, Instant now) {
        ReentryCampaign c = new ReentryCampaign();
        c.id = UuidV7.newId();
        c.status = Status.BORRADOR;
        c.targets = targetsJson;
        c.targetCount = count;
        c.createdBy = actor;
        c.createdAt = now;
        return c;
    }

    public void review(boolean approved, UUID reviewer, Instant now) {
        if (status != Status.BORRADOR) {
            throw DomainException.conflict("La campaña ya fue revisada");
        }
        this.status = approved ? Status.APROBADA : Status.DESCARTADA;
        this.reviewedBy = reviewer;
        this.reviewedAt = now;
    }

}
