package co.edu.retencion.risk.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.ai.ResourceCatalog;
import co.edu.retencion.ai.SupportResourceInfo;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.common.web.CursorPage;
import co.edu.retencion.risk.AssessmentSource;
import co.edu.retencion.risk.RiskLevel;
import co.edu.retencion.risk.RiskQueries;
import co.edu.retencion.risk.RiskTrigger;
import co.edu.retencion.risk.ScoreType;
import co.edu.retencion.risk.domain.Factor.FactorCategory;
import co.edu.retencion.risk.domain.FactorVisibility;
import co.edu.retencion.risk.domain.RiskAssessment;
import co.edu.retencion.risk.domain.RiskAssessmentRepository;

/**
 * Lectura del historial de riesgo aplicando el mínimo necesario por rol ({@link FactorVisibility}):
 * el docente ve nivel y motivos académicos; el texto de la IA se oculta a quien no puede ver el
 * contexto que la IA recibió. El puntaje de reglas nunca se presenta como probabilidad.
 */
@Service
public class RiskAssessmentService implements RiskQueries {

    static final String RULES_MEANING = "Puntaje de riesgo por reglas (0-100). No es una probabilidad.";
    static final String ML_MEANING = "Probabilidad calibrada de deserción del modelo predictivo.";
    static final String GUARDRAIL = "La IA recomienda, no decide: toda acción requiere revisión y aprobación humana.";

    private final RiskAssessmentRepository assessments;
    private final co.edu.retencion.risk.domain.RiskStudentStateRepository states;
    private final ResourceCatalog catalog;
    private final ObjectMapper mapper;

    public RiskAssessmentService(RiskAssessmentRepository assessments, ResourceCatalog catalog, ObjectMapper mapper,
                                 co.edu.retencion.risk.domain.RiskStudentStateRepository states) {
        this.assessments = assessments;
        this.states = states;
        this.catalog = catalog;
        this.mapper = mapper;
    }

    /** Factor del motor tal como se muestra. */
    public record FactorView(String code, FactorCategory category, BigDecimal points, String explanation,
                             Map<String, Object> details) {
    }

    public record RecommendationView(String resourceId, String resourceName, String resourceContact, String action,
                                     String priority, String responsibleRole) {
    }

    /** Parte generada por la IA (solo para quien puede verla). */
    public record AiView(RiskLevel suggestedLevel, String summary, List<Map<String, Object>> factors,
                         List<Map<String, Object>> probableCauses, List<RecommendationView> recommendations,
                         BigDecimal confidence) {
    }

    /** Trazabilidad del análisis. */
    public record Versions(String rulesVersion, String mlModelVersion, String aiModel, String promptVersion,
                           String payloadHash, int inputTokens, int outputTokens, int aiAttempts) {
    }

    /**
     * @param score        puntaje 0-100
     * @param scoreType    RULES o ML_CALIBRATED
     * @param probability  solo con ML_CALIBRATED (única que se muestra como probabilidad)
     * @param scoreMeaning cómo leer el puntaje
     * @param ai           salida de la IA, {@code null} si no la hubo o el rol no puede verla
     */
    public record AssessmentView(UUID id, UUID studentId, RiskLevel level, BigDecimal score, ScoreType scoreType,
                                 BigDecimal probability, String scoreMeaning, AssessmentSource source,
                                 RiskTrigger trigger, List<FactorView> factors, AiView ai, Versions versions,
                                 String fallbackReason, boolean requiresHumanReview, String guardrail,
                                 Instant createdAt) {
    }

    @Transactional(readOnly = true)
    public CursorPage<AssessmentView> history(UUID studentId, UUID after, Integer size) {
        int limit = CursorPage.clampSize(size);
        Set<Role> roles = CurrentUser.require().roles();
        List<AssessmentView> rows = assessments.history(studentId, after == null, after, Limit.of(limit + 1)).stream()
                .map(a -> view(a, roles)).toList();
        return CursorPage.of(rows, limit, AssessmentView::id);
    }

    @Transactional(readOnly = true)
    public Optional<AssessmentView> latestView(UUID studentId) {
        Set<Role> roles = CurrentUser.require().roles();
        return assessments.findFirstByStudentIdOrderByCreatedAtDescIdDesc(studentId).map(a -> view(a, roles));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RiskRow> page(RiskListQuery q) {
        java.util.Set<UUID> none = java.util.Set.of(new UUID(0, 0));
        return states.page(q.levels(), q.programIds() == null, q.programIds() == null ? none : q.programIds(),
                        q.studentIds() == null, q.studentIds() == null ? none : q.studentIds(), q.after() == null, q.after(),
                        org.springframework.data.domain.Limit.of(q.limit())).stream()
                .map(s -> new RiskRow(s.getStudentId(), s.getProgramId(), s.getSemester(), s.getLastLevel(), s.getLastScore(),
                        s.getLastAssessedAt())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Map<RiskLevel, Long> levelCounts(java.util.Collection<UUID> studentIds) {
        return states.findByStudentIdIn(studentIds).stream().filter(s -> s.getLastLevel() != null)
                .collect(java.util.stream.Collectors.groupingBy(co.edu.retencion.risk.domain.RiskStudentState::getLastLevel,
                        java.util.stream.Collectors.counting()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LatestRisk> latest(UUID studentId) {
        return assessments.findFirstByStudentIdOrderByCreatedAtDescIdDesc(studentId)
                .map(a -> new LatestRisk(a.getId(), a.getLevel(), a.getScore(), a.getScoreType(), a.getCreatedAt()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<LatestRisk> history(UUID studentId, int limit) {
        return assessments.history(studentId, true, null, Limit.of(Math.max(1, Math.min(limit, 200)))).stream()
                .map(a -> new LatestRisk(a.getId(), a.getLevel(), a.getScore(), a.getScoreType(), a.getCreatedAt()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AssessmentSummary> summary(UUID assessmentId) {
        return assessments.findById(assessmentId).map(a -> new AssessmentSummary(a.getId(), a.getStudentId(),
                a.getLevel(), a.getScore(), a.getScoreType(), a.isSensitiveContext(),
                this.<List<FactorView>>read(a.getFactors(), new TypeReference<>() { }).stream()
                        .map(f -> new FactorSummary(f.code(), f.category().name(), f.points(), f.explanation(),
                                f.details())).toList(),
                this.<List<Map<String, Object>>>read(a.getRecommendations(), new TypeReference<>() { }).stream()
                        .map(r -> new SuggestedAction((String) r.get("resourceId"), (String) r.get("action"),
                                (String) r.get("priority"), (String) r.get("responsibleRole"))).toList(),
                a.getCreatedAt()));
    }

    public AssessmentView view(RiskAssessment a, Set<Role> roles) {
        Set<FactorCategory> visible = FactorVisibility.categoriesFor(roles);
        List<FactorView> factors = this.<List<FactorView>>read(a.getFactors(), new TypeReference<>() { }).stream()
                .filter(f -> visible.contains(f.category())).toList();
        AiView ai = null;
        if (a.getAiSummary() != null && FactorVisibility.canSeeAiText(roles, a.isSensitiveContext())) {
            boolean health = FactorVisibility.canSeeHealthCauses(roles);
            List<Map<String, Object>> causes = this.<List<Map<String, Object>>>read(a.getProbableCauses(),
                    new TypeReference<>() { }).stream().filter(c -> health || !"SALUD".equals(c.get("category"))).toList();
            List<RecommendationView> recommendations = this.<List<Map<String, Object>>>read(a.getRecommendations(),
                    new TypeReference<>() { }).stream().map(this::recommendation).toList();
            ai = new AiView(a.getAiLevel(), a.getAiSummary(), read(a.getAiFactors(), new TypeReference<>() { }), causes,
                    recommendations, a.getConfidence());
        }
        boolean ml = a.getScoreType() == ScoreType.ML_CALIBRATED;
        return new AssessmentView(a.getId(), a.getStudentId(), a.getLevel(), a.getScore(), a.getScoreType(),
                ml ? a.getProbability() : null, ml ? ML_MEANING : RULES_MEANING, a.getSource(), a.getTrigger(),
                factors, ai, new Versions(a.getRulesVersion(), a.getMlModelVersion(), a.getAiModel(),
                a.getPromptVersion(), a.getPayloadHash(), a.getInputTokens(), a.getOutputTokens(), a.getAiAttempts()),
                a.getFallbackReason(), true, GUARDRAIL, a.getCreatedAt());
    }

    private RecommendationView recommendation(Map<String, Object> r) {
        String id = (String) r.get("resourceId");
        Optional<SupportResourceInfo> resource = id == null ? Optional.empty() : catalog.find(id);
        return new RecommendationView(id, resource.map(SupportResourceInfo::name).orElse(null),
                resource.map(SupportResourceInfo::contact).orElse(null), (String) r.get("action"),
                (String) r.get("priority"), (String) r.get("responsibleRole"));
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Análisis con JSON ilegible", ex);
        }
    }
}
