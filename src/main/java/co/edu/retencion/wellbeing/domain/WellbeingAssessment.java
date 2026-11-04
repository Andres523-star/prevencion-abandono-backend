package co.edu.retencion.wellbeing.domain;

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

import co.edu.retencion.common.crypto.EncryptedStringConverter;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.id.UuidV7;

/** Evaluación física o psicológica. Resumen y hallazgos cifrados en columna (dato sensible). */
@Entity
@Table(name = "wellbeing_assessments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WellbeingAssessment {

    public enum Type {
        FISICA,
        PSICOLOGICA
    }

    @Id
    private UUID id;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "assessment_type", nullable = false)
    private Type type;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "summary_ciphertext", nullable = false)
    private String summary;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "findings_ciphertext")
    private String findings;

    @Column(name = "assessed_by", nullable = false)
    private UUID assessedBy;

    @Column(name = "assessed_on", nullable = false)
    private LocalDate assessedOn;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static WellbeingAssessment record(UUID studentId, Type type, String summary, String findings,
                                             UUID assessedBy, LocalDate assessedOn, Instant now) {
        if (summary == null || summary.isBlank() || summary.length() > 4000) {
            throw DomainException.badRequest("El resumen debe tener entre 1 y 4000 caracteres");
        }
        if (findings != null && findings.length() > 8000) {
            throw DomainException.badRequest("Los hallazgos no pueden superar 8000 caracteres");
        }
        WellbeingAssessment a = new WellbeingAssessment();
        a.id = UuidV7.newId();
        a.studentId = studentId;
        a.type = type;
        a.summary = summary.trim();
        a.findings = findings == null || findings.isBlank() ? null : findings.trim();
        a.assessedBy = assessedBy;
        a.assessedOn = assessedOn;
        a.createdAt = now;
        return a;
    }

    @Override
    public String toString() {
        return "WellbeingAssessment[id=" + id + ", type=" + type + "]";
    }
}
