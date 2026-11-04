package co.edu.retencion.risk.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.academic.SubjectInfo;
import co.edu.retencion.ai.AiCallInfo;
import co.edu.retencion.ai.Anonymizer;
import co.edu.retencion.ai.Anonymizer.AnonymizedPayload;
import co.edu.retencion.ai.Anonymizer.PlanTarget;
import co.edu.retencion.ai.PayloadRejectedException;
import co.edu.retencion.ai.ResourceCatalog;
import co.edu.retencion.ai.StudentTextPlaceholders;
import co.edu.retencion.ai.SupportPlanWriter;
import co.edu.retencion.ai.SupportResourceInfo;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.common.security.authorization.AccessAction;
import co.edu.retencion.common.security.authorization.ScopeAuthorizationService;
import co.edu.retencion.records.AttendanceLevel;
import co.edu.retencion.records.RecordsSnapshot.SubjectSnapshot;
import co.edu.retencion.risk.domain.RiskFeatures;
import co.edu.retencion.risk.domain.SupportPlan;
import co.edu.retencion.risk.domain.SupportPlanRepository;
import co.edu.retencion.risk.domain.SupportPlanTemplates;
import co.edu.retencion.risk.domain.SupportPlanTemplates.PlanAction;
import co.edu.retencion.students.StudentRegistry;
import co.edu.retencion.students.TransportMode;

/**
 * Plan de apoyo por asistencia: al alcanzar un umbral se propone un plan para que el estudiante no
 * pierda la materia. Desde {@code ai.support-plan.min-level} (por defecto CRÍTICO) lo redacta la IA
 * con el modelo capaz; antes, o si la IA falla, se usa la plantilla de reglas. Siempre queda
 * PROPUESTO hasta que un humano lo aprueba o lo rechaza.
 *
 * <p>Los textos al estudiante llevan {@code {{nombre}}} y {@code {{materia}}}: el nombre se rellena
 * solo al leer el plan un rol autorizado (lectura auditada); la IA nunca lo recibe.
 */
@Service
public class SupportPlanService {

    private final RiskFeatureCollector collector;
    private final RiskSettings riskSettings;
    private final ScorerFactory scorers;
    private final ResourceCatalog catalog;
    private final Anonymizer anonymizer;
    private final SupportPlanWriter writer;
    private final SupportPlanRepository plans;
    private final StudentRegistry students;
    private final AcademicDirectory academic;
    private final ScopeAuthorizationService scope;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    public SupportPlanService(RiskFeatureCollector collector, RiskSettings riskSettings, ScorerFactory scorers,
                              ResourceCatalog catalog, Anonymizer anonymizer, SupportPlanWriter writer,
                              SupportPlanRepository plans, StudentRegistry students, AcademicDirectory academic,
                              ScopeAuthorizationService scope, ObjectMapper mapper, TransactionTemplate tx,
                              Clock clock) {
        this.collector = collector;
        this.riskSettings = riskSettings;
        this.scorers = scorers;
        this.catalog = catalog;
        this.anonymizer = anonymizer;
        this.writer = writer;
        this.plans = plans;
        this.students = students;
        this.academic = academic;
        this.scope = scope;
        this.mapper = mapper;
        this.tx = tx;
        this.clock = clock;
    }

    /** Vista del plan. {@code studentMessage} va con marcadores salvo en la vista renderizada. */
    public record PlanView(UUID id, UUID studentId, UUID enrollmentId, UUID groupId, String subjectName,
                           AttendanceLevel triggerLevel, SupportPlan.Status status, SupportPlan.Source source,
                           List<PlanAction> actions, String studentMessage, boolean rendered, String aiModel,
                           String promptVersion, String fallbackReason, boolean requiresHumanApproval,
                           Instant createdAt, UUID reviewedBy, Instant reviewedAt) {

        @Override
        public String toString() {
            return "PlanView[" + id + "]";
        }
    }

    /** Propone el plan (lo llama el worker al procesar un umbral). */
    public Optional<SupportPlan> propose(UUID studentId, UUID enrollmentId, UUID groupId, AttendanceLevel level) {
        Optional<RiskFeatures> features = collector.collect(studentId);
        if (features.isEmpty() || level == null || level == AttendanceLevel.NINGUNO) {
            return Optional.empty();
        }
        RiskFeatures f = features.get();
        List<SubjectSnapshot> subjects = f.records().subjects();
        int index = -1;
        for (int i = 0; i < subjects.size(); i++) {
            if (subjects.get(i).enrollmentId().equals(enrollmentId)) {
                index = i;
            }
        }
        if (index < 0) {
            // La inscripción ya no está activa en el periodo actual: no hay materia que salvar.
            return Optional.empty();
        }
        SubjectSnapshot subject = subjects.get(index);
        RiskSettings.Snapshot settings = riskSettings.snapshot();
        List<SupportResourceInfo> resources = catalog.eligible(collector.eligibility(f));
        Set<String> eligibleIds = resources.stream().map(SupportResourceInfo::id).collect(Collectors.toSet());

        List<PlanAction> actions = null;
        String message = null;
        SupportPlan.Source source = SupportPlan.Source.RULES;
        AiCallInfo call = null;
        String fallback = "BELOW_MIN_LEVEL";
        if (level.ordinal() >= settings.planAiMinLevel().ordinal()) {
            try {
                AnonymizedPayload payload = anonymizer.anonymize(
                        collector.caseFacts(f, scorers.create(settings).score(f), List.of()), resources,
                        new PlanTarget(RiskFeatureCollector.subjectRef(index), level.name()));
                SupportPlanWriter.Outcome outcome = writer.draft(payload, eligibleIds);
                call = outcome.call();
                if (outcome.draft().isPresent()) {
                    actions = outcome.draft().get().actions().stream().map(a -> new PlanAction(a.type(), a.title(),
                            a.description(), a.priority(), a.suggestedOwner(), a.resourceId(), a.dueInDays())).toList();
                    message = outcome.draft().get().studentMessage();
                    source = SupportPlan.Source.AI;
                    fallback = null;
                } else {
                    fallback = call.failure().name();
                }
            } catch (PayloadRejectedException ex) {
                fallback = "POLICY_VIOLATION";
            }
        }
        if (actions == null) {
            boolean publicTransport = f.socioeconomic() != null
                    && f.socioeconomic().transport() == TransportMode.TRANSPORTE_PUBLICO;
            boolean works = f.socioeconomic() != null && Boolean.TRUE.equals(f.socioeconomic().works());
            SupportPlanTemplates.PlanTemplate template = SupportPlanTemplates.forLevel(level,
                    new SupportPlanTemplates.Context(subject.belowPassing(), works, publicTransport, eligibleIds));
            actions = template.actions();
            message = template.studentMessage();
        }
        String actionsJson = json(actions);
        String finalMessage = message;
        SupportPlan.Source finalSource = source;
        AiCallInfo finalCall = call;
        String finalFallback = fallback;
        return tx.execute(status -> {
            Optional<SupportPlan> open = plans.findByEnrollmentIdAndStatus(enrollmentId, SupportPlan.Status.PROPUESTO);
            if (open.isPresent() && open.get().getTriggerLevel().ordinal() >= level.ordinal()) {
                return Optional.<SupportPlan>empty();
            }
            open.ifPresent(p -> {
                p.supersede(clock.instant());
                plans.saveAndFlush(p);
            });
            return Optional.of(plans.save(SupportPlan.propose(studentId, enrollmentId, groupId, level, finalSource,
                    actionsJson, finalMessage, finalCall == null ? null : finalCall.model(),
                    finalCall == null ? null : finalCall.promptVersion(),
                    finalCall == null ? null : finalCall.payloadHash(), finalFallback, clock.instant())));
        });
    }

    @Transactional(readOnly = true)
    public List<PlanView> ofStudent(UUID studentId) {
        return plans.findByStudentIdOrderByCreatedAtDesc(studentId).stream().map(p -> view(p, false)).toList();
    }

    /** Plan con los marcadores rellenados (lee el nombre: el controlador audita la lectura). */
    @Transactional(readOnly = true)
    public PlanView rendered(UUID planId) {
        SupportPlan plan = load(planId, AccessAction.READ);
        return view(plan, true);
    }

    @Transactional
    public PlanView approve(UUID planId, String comment) {
        SupportPlan plan = load(planId, AccessAction.WRITE);
        plan.approve(CurrentUser.require().userId(), comment, clock.instant());
        return view(plans.saveAndFlush(plan), false);
    }

    @Transactional
    public PlanView reject(UUID planId, String comment) {
        SupportPlan plan = load(planId, AccessAction.WRITE);
        plan.reject(CurrentUser.require().userId(), comment, clock.instant());
        return view(plans.saveAndFlush(plan), false);
    }

    private SupportPlan load(UUID planId, AccessAction action) {
        SupportPlan plan = plans.findById(planId).orElseThrow(() -> DomainException.notFound("Plan no encontrado"));
        if (!scope.canAccess("STUDENT", plan.getStudentId(), action)) {
            // Fuera de alcance responde 404: no revela que el plan existe.
            throw DomainException.notFound("Plan no encontrado");
        }
        return plan;
    }

    private PlanView view(SupportPlan p, boolean render) {
        String subject = academic.findGroup(p.getGroupId()).flatMap(g -> academic.findSubject(g.subjectId()))
                .map(SubjectInfo::name).orElse(null);
        List<PlanAction> actions = parse(p.getActions());
        String message = p.getStudentMessage();
        if (render) {
            String name = students.givenName(p.getStudentId()).orElse(null);
            message = StudentTextPlaceholders.render(message, name, subject);
            actions = actions.stream().map(a -> new PlanAction(a.type(), a.title(),
                    StudentTextPlaceholders.render(a.description(), name, subject), a.priority(), a.suggestedOwner(),
                    a.resourceId(), a.dueInDays())).toList();
        }
        return new PlanView(p.getId(), p.getStudentId(), p.getEnrollmentId(), p.getGroupId(), subject,
                p.getTriggerLevel(), p.getStatus(), p.getSource(), actions, message, render, p.getAiModel(),
                p.getPromptVersion(), p.getFallbackReason(), true, p.getCreatedAt(), p.getReviewedBy(),
                p.getReviewedAt());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private List<PlanAction> parse(String json) {
        try {
            return mapper.readValue(json, new TypeReference<List<PlanAction>>() { });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Plan con acciones ilegibles", ex);
        }
    }
}
