package co.edu.retencion.risk.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import co.edu.retencion.ai.AiCallInfo;
import co.edu.retencion.ai.Anonymizer;
import co.edu.retencion.ai.Anonymizer.AnonymizedPayload;
import co.edu.retencion.ai.CaseFacts;
import co.edu.retencion.ai.ModelTier;
import co.edu.retencion.ai.PayloadRejectedException;
import co.edu.retencion.ai.ResourceCatalog;
import co.edu.retencion.ai.RiskExplainer;
import co.edu.retencion.ai.SupportResourceInfo;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.records.AttendanceLevel;
import co.edu.retencion.risk.AssessmentSource;
import co.edu.retencion.risk.RiskAssessed;
import co.edu.retencion.risk.RiskLevel;
import co.edu.retencion.risk.RiskTrigger;
import co.edu.retencion.risk.ScoreType;
import co.edu.retencion.risk.domain.Factor;
import co.edu.retencion.risk.domain.RiskAssessment;
import co.edu.retencion.risk.domain.RiskAssessmentRepository;
import co.edu.retencion.risk.domain.RiskFeatures;
import co.edu.retencion.risk.domain.RiskScorer.ScoreResult;
import co.edu.retencion.risk.domain.RiskStudentState;
import co.edu.retencion.risk.domain.RiskStudentStateRepository;

/**
 * Orquestador del diseño híbrido: siempre calcula el puntaje (reglas o ML) y decide si además
 * pide a la IA explicación y recomendaciones.
 *
 * <p>Política (documentada en {@code docs/motor-de-riesgo.md}):
 * <ul>
 *   <li>Manual: siempre se pide IA; modelo capaz si el nivel es MEDIO o ALTO.</li>
 *   <li>Evento y lote: solo si el nivel alcanza {@code ai.min-level} (por defecto MEDIO) o hay un
 *       umbral de asistencia CRÍTICO/PÉRDIDA; modelo económico salvo riesgo ALTO o umbral crítico.</li>
 *   <li>Si la IA no responde, responde mal, se agota el presupuesto o el payload no pasa la política
 *       de privacidad, el análisis se guarda igual con el motor de reglas y el motivo del fallback.</li>
 *   <li>La IA no decide: el nivel es el del motor; solo puede subirlo si {@code risk.ai-can-escalate}.</li>
 * </ul>
 * Ninguna llamada a la IA ocurre dentro de una transacción de base de datos.
 */
@Service
public class RiskOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RiskOrchestrator.class);

    private final RiskFeatureCollector collector;
    private final RiskSettings riskSettings;
    private final ScorerFactory scorers;
    private final ResourceCatalog catalog;
    private final Anonymizer anonymizer;
    private final RiskExplainer explainer;
    private final RiskAssessmentRepository assessments;
    private final RiskStudentStateRepository states;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final Clock clock;

    public RiskOrchestrator(RiskFeatureCollector collector, RiskSettings riskSettings, ScorerFactory scorers,
                            ResourceCatalog catalog, Anonymizer anonymizer, RiskExplainer explainer,
                            RiskAssessmentRepository assessments, RiskStudentStateRepository states,
                            ApplicationEventPublisher events, TransactionTemplate tx, ObjectMapper mapper,
                            Clock clock) {
        this.collector = collector;
        this.riskSettings = riskSettings;
        this.scorers = scorers;
        this.catalog = catalog;
        this.anonymizer = anonymizer;
        this.explainer = explainer;
        this.assessments = assessments;
        this.states = states;
        this.events = events;
        this.tx = tx;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * Variables, puntaje y payload ya calculados (el lote nocturno los usa para decidir por hash si
     * hace falta un análisis).
     *
     * @param payload {@code null} si el payload violó la política de privacidad
     */
    public record Prepared(RiskFeatures features, RiskSettings.Snapshot settings, ScoreResult score,
                           List<SupportResourceInfo> resources, AnonymizedPayload payload, String payloadHash) {

        @Override
        public String toString() {
            return "Prepared[" + features.anonCode() + "]";
        }
    }

    /** Decisión de IA para un análisis. */
    record AiDecision(boolean call, ModelTier tier, String skipReason) {
    }

    public Optional<Prepared> prepare(UUID studentId) {
        Optional<RiskFeatures> features = collector.collect(studentId);
        if (features.isEmpty()) {
            return Optional.empty();
        }
        RiskFeatures f = features.get();
        RiskSettings.Snapshot settings = riskSettings.snapshot();
        ScoreResult score = scorers.create(settings).score(f);
        List<SupportResourceInfo> resources = catalog.eligible(collector.eligibility(f));
        CaseFacts facts = collector.caseFacts(f, score, collector.freeTexts(studentId, settings.freeText()));
        AnonymizedPayload payload = null;
        String hash;
        try {
            payload = anonymizer.anonymize(facts, resources, null);
            hash = payload.sha256();
        } catch (PayloadRejectedException ex) {
            log.warn("Payload de IA rechazado por la política de privacidad: {}", ex.violations());
            hash = RiskJson.sha256("rejected:" + f.studentId() + ":" + score.version());
        }
        return Optional.of(new Prepared(f, settings, score, resources, payload, hash));
    }

    public RiskAssessment analyze(UUID studentId, RiskTrigger trigger, UUID requestedBy, AttendanceLevel threshold) {
        Prepared p = prepare(studentId).orElseThrow(() -> DomainException.notFound("Estudiante no encontrado"));
        return analyzePrepared(p, trigger, requestedBy, threshold);
    }

    public RiskAssessment analyzePrepared(Prepared p, RiskTrigger trigger, UUID requestedBy, AttendanceLevel threshold) {
        AiDecision decision = decide(p, trigger, threshold);
        RiskExplainer.Explanation explanation = null;
        AiCallInfo call = null;
        String fallback = decision.skipReason();
        if (decision.call()) {
            RiskExplainer.Outcome outcome = explainer.explain(p.payload(), resourceIds(p.resources()), decision.tier());
            call = outcome.call();
            explanation = outcome.explanation().orElse(null);
            if (!call.succeeded()) {
                fallback = call.failure().name();
            }
        }
        RiskLevel scorerLevel = p.score().level();
        RiskLevel aiLevel = explanation == null ? null : RiskLevel.valueOf(explanation.level());
        RiskLevel level = scorerLevel;
        AssessmentSource source = explanation == null ? AssessmentSource.RULES : AssessmentSource.HYBRID;
        if (aiLevel != null && p.settings().aiCanEscalate() && aiLevel.isAbove(scorerLevel)) {
            level = aiLevel;
            source = AssessmentSource.AI;
        }
        Integer stratum = p.features().socioeconomic() == null ? null : p.features().socioeconomic().stratum();
        RiskAssessment.Data data = new RiskAssessment.Data(p.features().studentId(), p.features().records().termId(),
                level, scorerLevel, aiLevel, p.score().score(), p.score().scoreType(), p.score().probability(), source,
                trigger, json(p.score().factors().stream().map(RiskOrchestrator::factorJson).toList()),
                explanation == null ? null : explanation.summary(),
                explanation == null ? null : json(explanation.factors()),
                explanation == null ? null : json(explanation.recommendations()),
                explanation == null ? null : json(explanation.probableCauses()),
                explanation == null ? null : explanation.confidence(),
                call != null && call.sensitiveContext(), p.score().rulesVersion(),
                p.score().scoreType() == ScoreType.ML_CALIBRATED ? p.score().version() : null,
                call == null ? null : call.model(), call == null ? null : call.promptVersion(), p.payloadHash(),
                call == null ? 0 : call.inputTokens(), call == null ? 0 : call.outputTokens(),
                call == null ? 0 : call.attempts(), explanation == null ? fallback : null,
                p.features().programId(), p.features().semester(), stratum, requestedBy);
        RiskLevel finalLevel = level;
        return tx.execute(status -> persist(data, p.score(), finalLevel, p.settings().watchDays()));
    }

    private RiskAssessment persist(RiskAssessment.Data data, ScoreResult score, RiskLevel level, int watchDays) {
        Instant now = clock.instant();
        RiskAssessment saved = assessments.save(RiskAssessment.record(data, now));
        RiskStudentState state = states.findForUpdate(data.studentId())
                .orElseGet(() -> RiskStudentState.of(data.studentId(), now));
        RiskLevel previous = state.getLastLevel();
        state.assessed(saved, now);
        if (level == RiskLevel.ALTO) {
            state.watch(now.plus(Duration.ofDays(watchDays)), "RIESGO_ALTO", now);
        }
        states.save(state);
        io.micrometer.core.instrument.Metrics.counter("risk.assessments", "level", level.name(), "source",
                saved.getSource().name(), "trigger", saved.getTrigger().name()).increment();
        events.publishEvent(new RiskAssessed(saved.getId(), saved.getStudentId(), level, previous, saved.getScore(),
                saved.getScoreType(), saved.getSource(), saved.getTrigger(),
                score.factors().stream().map(Factor::code).distinct().toList()));
        return saved;
    }

    AiDecision decide(Prepared p, RiskTrigger trigger, AttendanceLevel threshold) {
        if (p.payload() == null) {
            return new AiDecision(false, null, "POLICY_VIOLATION");
        }
        RiskLevel level = p.score().level();
        boolean criticalThreshold = threshold == AttendanceLevel.CRITICO || threshold == AttendanceLevel.PERDIDA;
        boolean critical = level == RiskLevel.ALTO || criticalThreshold;
        if (trigger == RiskTrigger.MANUAL) {
            return new AiDecision(true, critical || level == RiskLevel.MEDIO ? ModelTier.DEFAULT : ModelTier.TRIAGE,
                    null);
        }
        if (level.ordinal() < p.settings().aiMinLevel().ordinal() && !criticalThreshold) {
            return new AiDecision(false, null, "BELOW_MIN_LEVEL");
        }
        return new AiDecision(true, critical ? ModelTier.DEFAULT : ModelTier.TRIAGE, null);
    }

    private static Set<String> resourceIds(List<SupportResourceInfo> resources) {
        return resources.stream().map(SupportResourceInfo::id).collect(Collectors.toSet());
    }

    private static java.util.Map<String, Object> factorJson(Factor f) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("code", f.code());
        m.put("category", f.category().name());
        m.put("points", f.points());
        m.put("explanation", f.explanation());
        m.put("details", f.details());
        return m;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("No se pudo serializar el análisis", ex);
        }
    }
}
