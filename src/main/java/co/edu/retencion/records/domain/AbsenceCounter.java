package co.edu.retencion.records.domain;

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

import co.edu.retencion.records.AttendanceLevel;
import co.edu.retencion.records.CounterInfo;

/**
 * Contador materializado de faltas por inscripción. Siempre es recalculable desde
 * {@code attendance_records} ({@link AbsenceTally}); se guarda para consultas rápidas y para
 * detectar cuándo sube de nivel.
 */
@Entity
@Table(name = "absence_counters")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AbsenceCounter {

    @Id
    @Column(name = "enrollment_id")
    private UUID enrollmentId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "term_id", nullable = false)
    private UUID termId;

    @Column(nullable = false)
    private int unjustified;

    @Column(nullable = false)
    private int pending;

    @Column(nullable = false)
    private int justified;

    @Column(nullable = false)
    private int tardies;

    @Column(name = "counted_absences", nullable = false)
    private int countedAbsences;

    @Column(name = "max_absences", nullable = false)
    private int maxAbsences;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AttendanceLevel level;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public static AbsenceCounter empty(UUID enrollmentId, UUID studentId, UUID groupId, UUID termId, int maxAbsences,
                                       Instant now) {
        AbsenceCounter c = new AbsenceCounter();
        c.enrollmentId = enrollmentId;
        c.studentId = studentId;
        c.groupId = groupId;
        c.termId = termId;
        c.maxAbsences = maxAbsences;
        c.level = AttendanceLevel.NINGUNO;
        c.updatedAt = now;
        return c;
    }

    /**
     * Aplica un nuevo cálculo.
     *
     * @return el nivel que tenía antes (para decidir si se emite un evento de umbral)
     */
    public AttendanceLevel apply(AbsenceTally tally, int maxAbsences, Instant now) {
        AttendanceLevel previous = this.level;
        this.unjustified = tally.unjustified();
        this.pending = tally.pending();
        this.justified = tally.justified();
        this.tardies = tally.tardies();
        this.countedAbsences = tally.countedAbsences();
        this.maxAbsences = maxAbsences;
        this.level = tally.level();
        this.updatedAt = now;
        return previous;
    }

    public CounterInfo info() {
        return new CounterInfo(enrollmentId, groupId, unjustified, pending, justified, tardies, countedAbsences,
                maxAbsences, level);
    }
}
