package co.edu.retencion.students.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import co.edu.retencion.common.id.UuidV7;

/**
 * Inscripción de un estudiante en un grupo de un periodo. Retirarse de la materia cambia el
 * estado (no se borra: el historial alimenta el análisis de riesgo).
 */
@Entity
@Table(name = "enrollments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Enrollment {

    /** Estado de la inscripción. */
    public enum Status {
        ACTIVA,
        RETIRADA
    }

    @Id
    private UUID id;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "term_id", nullable = false)
    private UUID termId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "enrolled_at", nullable = false)
    private Instant enrolledAt;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    @Version
    private long version;

    public static Enrollment of(UUID studentId, UUID groupId, UUID termId, Instant now) {
        Enrollment e = new Enrollment();
        e.id = UuidV7.newId();
        e.studentId = studentId;
        e.groupId = groupId;
        e.termId = termId;
        e.status = Status.ACTIVA;
        e.enrolledAt = now;
        return e;
    }

    public boolean isActive() {
        return status == Status.ACTIVA;
    }

    public void withdraw(Instant now) {
        this.status = Status.RETIRADA;
        this.withdrawnAt = now;
    }

    public void reactivate(Instant now) {
        this.status = Status.ACTIVA;
        this.withdrawnAt = null;
        this.enrolledAt = now;
    }
}
