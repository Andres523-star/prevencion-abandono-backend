package co.edu.retencion.students.domain;

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

import co.edu.retencion.common.id.UuidV7;

/**
 * Registro de consentimiento de tratamiento de datos (Ley 1581 de 2012): versión de la política,
 * finalidad, otorgado o revocado, canal y fecha. Inmutable: cada cambio es una fila nueva y el
 * estado vigente es la más reciente por finalidad.
 */
@Entity
@Table(name = "data_consents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DataConsent {

    /** Finalidad del tratamiento. */
    public enum Purpose {
        TRATAMIENTO_ACADEMICO,
        ANALITICA_RETENCION,
        BIENESTAR,
        COMUNICACIONES
    }

    /** Canal por el que se registró. */
    public enum Channel {
        PORTAL,
        IMPORTACION,
        PRESENCIAL
    }

    @Id
    private UUID id;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "policy_version", nullable = false)
    private String policyVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Purpose purpose;

    @Column(nullable = false)
    private boolean granted;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "recorded_by")
    private UUID recordedBy;

    public static DataConsent record(UUID studentId, String policyVersion, Purpose purpose, boolean granted,
                                     Channel channel, UUID recordedBy, Instant now) {
        DataConsent c = new DataConsent();
        c.id = UuidV7.newId();
        c.studentId = studentId;
        c.policyVersion = policyVersion;
        c.purpose = purpose;
        c.granted = granted;
        c.channel = channel;
        c.recordedBy = recordedBy;
        c.recordedAt = now;
        return c;
    }
}
