package co.edu.retencion.academic.domain;

import java.time.Instant;
import java.util.Optional;
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

import co.edu.retencion.academic.EffectiveAttendancePolicy;
import co.edu.retencion.academic.EffectiveAttendancePolicy.PolicySource;
import co.edu.retencion.academic.GroupType;
import co.edu.retencion.common.id.UuidV7;

/**
 * Política de asistencia configurable por programa, por programa para prácticas clínicas o por
 * materia. Resolución (la más específica gana): materia &gt; práctica clínica &gt; programa &gt; defecto.
 */
@Entity
@Table(name = "attendance_policies")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttendancePolicy {

    /** Alcance de la política. */
    public enum Scope {
        PROGRAM,
        PROGRAM_CLINICAL,
        SUBJECT
    }

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Scope scope;

    @Column(name = "program_id")
    private UUID programId;

    @Column(name = "subject_id")
    private UUID subjectId;

    @Column(name = "max_absences", nullable = false)
    private int maxAbsences;

    @Column(name = "warning_threshold", nullable = false)
    private int warningThreshold;

    @Column(name = "critical_threshold", nullable = false)
    private int criticalThreshold;

    @Column(name = "justification_window_days", nullable = false)
    private int justificationWindowDays;

    @Column(name = "tardies_per_absence")
    private Integer tardiesPerAbsence;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Version
    private long version;

    public static AttendancePolicy forProgram(UUID programId, boolean clinical, PolicyValues values, UUID actor,
                                              Instant now) {
        AttendancePolicy p = new AttendancePolicy();
        p.id = UuidV7.newId();
        p.scope = clinical ? Scope.PROGRAM_CLINICAL : Scope.PROGRAM;
        p.programId = programId;
        p.apply(values, actor, now);
        return p;
    }

    public static AttendancePolicy forSubject(UUID subjectId, PolicyValues values, UUID actor, Instant now) {
        AttendancePolicy p = new AttendancePolicy();
        p.id = UuidV7.newId();
        p.scope = Scope.SUBJECT;
        p.subjectId = subjectId;
        p.apply(values, actor, now);
        return p;
    }

    public void apply(PolicyValues values, UUID actor, Instant now) {
        this.maxAbsences = values.maxAbsences();
        this.warningThreshold = values.warningThreshold();
        this.criticalThreshold = values.criticalThreshold();
        this.justificationWindowDays = values.justificationWindowBusinessDays();
        this.tardiesPerAbsence = values.tardiesPerAbsence();
        this.updatedBy = actor;
        this.updatedAt = now;
    }

    public PolicyValues values() {
        return new PolicyValues(maxAbsences, warningThreshold, criticalThreshold, justificationWindowDays,
                tardiesPerAbsence);
    }

    /**
     * Resuelve la política efectiva de un grupo a partir de las candidatas encontradas.
     *
     * @param subject  política de la materia, si existe
     * @param clinical política de prácticas clínicas del programa, si existe
     * @param program  política del programa, si existe
     * @param type     tipo del grupo (la clínica solo aplica a prácticas)
     */
    public static EffectiveAttendancePolicy resolve(Optional<AttendancePolicy> subject,
                                                    Optional<AttendancePolicy> clinical,
                                                    Optional<AttendancePolicy> program,
                                                    GroupType type) {
        if (subject.isPresent()) {
            return effective(subject.get().values(), PolicySource.SUBJECT);
        }
        if (type == GroupType.PRACTICA_CLINICA && clinical.isPresent()) {
            return effective(clinical.get().values(), PolicySource.PROGRAM_CLINICAL);
        }
        return program.map(p -> effective(p.values(), PolicySource.PROGRAM))
                .orElseGet(() -> effective(PolicyValues.DEFAULT, PolicySource.DEFAULT));
    }

    private static EffectiveAttendancePolicy effective(PolicyValues v, PolicySource source) {
        return new EffectiveAttendancePolicy(v.maxAbsences(), v.warningThreshold(), v.criticalThreshold(),
                v.justificationWindowBusinessDays(), v.tardiesPerAbsence(), source);
    }
}
