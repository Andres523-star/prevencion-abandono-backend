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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import co.edu.retencion.common.id.UuidV7;
import co.edu.retencion.risk.AssessmentSource;
import co.edu.retencion.risk.RiskLevel;
import co.edu.retencion.risk.RiskTrigger;
import co.edu.retencion.risk.ScoreType;

/**
 * Un análisis de riesgo. Es inmutable: cada análisis es una fila nueva, así queda el historial y la
 * evolución (la app no tiene permiso de UPDATE ni DELETE sobre la tabla). Guarda las versiones de
 * todo lo que lo produjo: reglas, modelo ML, modelo de IA, prompt y hash del payload.
 */
@Entity
@Immutable
@Table(name = "risk_assessments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RiskAssessment {

    @Id
    private UUID id;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "term_id")
    private UUID termId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RiskLevel level;

    @Enumerated(EnumType.STRING)
    @Column(name = "scorer_level", nullable = false)
    private RiskLevel scorerLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_level")
    private RiskLevel aiLevel;

    @Column(nullable = false)
    private BigDecimal score;

    @Enumerated(EnumType.STRING)
    @Column(name = "score_type", nullable = false)
    private ScoreType scoreType;

    private BigDecimal probability;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssessmentSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false)
    private RiskTrigger trigger;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String factors;

    @Column(name = "ai_summary")
    private String aiSummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_factors", nullable = false, columnDefinition = "jsonb")
    private String aiFactors;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String recommendations;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "probable_causes", nullable = false, columnDefinition = "jsonb")
    private String probableCauses;

    private BigDecimal confidence;

    @Column(name = "sensitive_context", nullable = false)
    private boolean sensitiveContext;

    @Column(name = "rules_version", nullable = false)
    private String rulesVersion;

    @Column(name = "ml_model_version")
    private String mlModelVersion;

    @Column(name = "ai_model")
    private String aiModel;

    @Column(name = "prompt_version")
    private String promptVersion;

    @Column(name = "payload_hash", nullable = false)
    private String payloadHash;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "ai_attempts", nullable = false)
    private int aiAttempts;

    @Column(name = "fallback_reason")
    private String fallbackReason;

    @Column(name = "program_id", nullable = false)
    private UUID programId;

    @Column(nullable = false)
    private int semester;

    private Integer stratum;

    @Column(name = "requested_by")
    private UUID requestedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Datos de un análisis nuevo (los JSON ya serializados).
     *
     * @param stratum solo para métricas de sesgo; nunca entra al puntaje
     */
    public record Data(UUID studentId, UUID termId, RiskLevel level, RiskLevel scorerLevel, RiskLevel aiLevel,
                       BigDecimal score, ScoreType scoreType, BigDecimal probability, AssessmentSource source,
                       RiskTrigger trigger, String factorsJson, String aiSummary, String aiFactorsJson,
                       String recommendationsJson, String probableCausesJson, BigDecimal confidence,
                       boolean sensitiveContext, String rulesVersion, String mlModelVersion, String aiModel,
                       String promptVersion, String payloadHash, long inputTokens, long outputTokens,
                       int aiAttempts, String fallbackReason, UUID programId, int semester, Integer stratum,
                       UUID requestedBy) {

        @Override
        public String toString() {
            return "RiskAssessment.Data[" + studentId + ", " + level + "]";
        }
    }

    public static RiskAssessment record(Data d, Instant now) {
        RiskAssessment a = new RiskAssessment();
        a.id = UuidV7.newId();
        a.studentId = d.studentId();
        a.termId = d.termId();
        a.level = d.level();
        a.scorerLevel = d.scorerLevel();
        a.aiLevel = d.aiLevel();
        a.score = d.score();
        a.scoreType = d.scoreType();
        a.probability = d.scoreType() == ScoreType.ML_CALIBRATED ? d.probability() : null;
        a.source = d.source();
        a.trigger = d.trigger();
        a.factors = d.factorsJson();
        a.aiSummary = d.aiSummary();
        a.aiFactors = d.aiFactorsJson() == null ? "[]" : d.aiFactorsJson();
        a.recommendations = d.recommendationsJson() == null ? "[]" : d.recommendationsJson();
        a.probableCauses = d.probableCausesJson() == null ? "[]" : d.probableCausesJson();
        a.confidence = d.confidence();
        a.sensitiveContext = d.sensitiveContext();
        a.rulesVersion = d.rulesVersion();
        a.mlModelVersion = d.mlModelVersion();
        a.aiModel = d.aiModel();
        a.promptVersion = d.promptVersion();
        a.payloadHash = d.payloadHash();
        a.inputTokens = (int) Math.min(Integer.MAX_VALUE, d.inputTokens());
        a.outputTokens = (int) Math.min(Integer.MAX_VALUE, d.outputTokens());
        a.aiAttempts = d.aiAttempts();
        a.fallbackReason = d.fallbackReason();
        a.programId = d.programId();
        a.semester = d.semester();
        a.stratum = d.stratum();
        a.requestedBy = d.requestedBy();
        a.createdAt = now;
        return a;
    }
}
