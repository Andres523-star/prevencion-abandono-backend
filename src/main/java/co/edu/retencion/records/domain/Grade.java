package co.edu.retencion.records.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.SQLRestriction;

import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.id.UuidV7;

/**
 * Nota registrada para una inscripción (escala 0.0–5.0, una decimal). La plataforma NO calcula la
 * nota oficial (no hay cortes ni porcentajes): solo usa las notas para indicadores de riesgo.
 */
@Entity
@Table(name = "grades")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Grade {

    public static final BigDecimal MIN = new BigDecimal("0.0");
    public static final BigDecimal MAX = new BigDecimal("5.0");
    public static final BigDecimal PASSING = new BigDecimal("3.0");

    @Id
    private UUID id;

    @Column(name = "enrollment_id", nullable = false)
    private UUID enrollmentId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "term_id", nullable = false)
    private UUID termId;

    @Column(name = "grade_value", nullable = false, precision = 2, scale = 1)
    private BigDecimal value;

    @Column(name = "assessed_on", nullable = false)
    private LocalDate assessedOn;

    private String description;

    @Column(name = "recorded_by")
    private UUID recordedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    private long version;

    public static Grade record(UUID enrollmentId, UUID studentId, UUID groupId, UUID termId, BigDecimal value,
                               LocalDate assessedOn, String description, UUID actor, Instant now) {
        Grade g = new Grade();
        g.id = UuidV7.newId();
        g.enrollmentId = enrollmentId;
        g.studentId = studentId;
        g.groupId = groupId;
        g.termId = termId;
        g.value = normalize(value);
        g.assessedOn = Objects.requireNonNull(assessedOn, "assessedOn");
        g.description = description;
        g.recordedBy = actor;
        g.createdAt = now;
        g.updatedAt = now;
        return g;
    }

    /** @return {@code true} si cambió */
    public boolean correct(BigDecimal newValue, LocalDate assessed, String newDescription, UUID actor, Instant now) {
        BigDecimal normalized = normalize(newValue);
        boolean changed = normalized.compareTo(value) != 0 || !assessed.equals(assessedOn)
                || !Objects.equals(description, newDescription);
        if (changed) {
            this.value = normalized;
            this.assessedOn = assessed;
            this.description = newDescription;
            this.recordedBy = actor;
            this.updatedAt = now;
        }
        return changed;
    }

    public void delete(Instant now) {
        this.deletedAt = now;
        this.updatedAt = now;
    }

    /** Valida la escala y redondea a una decimal. */
    static BigDecimal normalize(BigDecimal value) {
        if (value == null || value.compareTo(MIN) < 0 || value.compareTo(MAX) > 0) {
            throw DomainException.badRequest("La nota debe estar entre 0.0 y 5.0");
        }
        if (value.scale() > 1 && value.setScale(1, RoundingMode.DOWN).compareTo(value) != 0) {
            throw DomainException.badRequest("La nota admite una sola cifra decimal");
        }
        return value.setScale(1, RoundingMode.UNNECESSARY);
    }
}
