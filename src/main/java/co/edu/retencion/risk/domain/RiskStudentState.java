package co.edu.retencion.risk.domain;

import java.math.BigDecimal;
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

import co.edu.retencion.risk.RiskLevel;

/**
 * Último estado del motor para un estudiante: hash del último payload (el lote nocturno solo
 * reanaliza si cambió), último nivel (para saber si subió) y vigilancia.
 */
@Entity
@Table(name = "risk_student_state")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RiskStudentState {

    @Id
    @Column(name = "student_id")
    private UUID studentId;

    @Column(name = "last_assessment_id")
    private UUID lastAssessmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_level")
    private RiskLevel lastLevel;

    @Column(name = "last_score")
    private BigDecimal lastScore;

    @Column(name = "last_payload_hash")
    private String lastPayloadHash;

    @Column(name = "last_assessed_at")
    private Instant lastAssessedAt;

    @Column(name = "program_id")
    private UUID programId;

    private Integer semester;

    private Integer stratum;

    @Column(name = "watch_until")
    private Instant watchUntil;

    @Column(name = "watch_reason")
    private String watchReason;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public static RiskStudentState of(UUID studentId, Instant now) {
        RiskStudentState s = new RiskStudentState();
        s.studentId = studentId;
        s.updatedAt = now;
        return s;
    }

    public void assessed(RiskAssessment a, Instant now) {
        this.lastAssessmentId = a.getId();
        this.lastLevel = a.getLevel();
        this.lastScore = a.getScore();
        this.lastPayloadHash = a.getPayloadHash();
        this.lastAssessedAt = a.getCreatedAt();
        this.programId = a.getProgramId();
        this.semester = a.getSemester();
        this.stratum = a.getStratum();
        this.updatedAt = now;
    }

    /** Pone (o extiende) la vigilancia. */
    public void watch(Instant until, String reason, Instant now) {
        if (watchUntil == null || until.isAfter(watchUntil)) {
            this.watchUntil = until;
            this.watchReason = reason;
        }
        this.updatedAt = now;
    }

    public boolean watched(Instant now) {
        return watchUntil != null && watchUntil.isAfter(now);
    }
}
