package co.edu.retencion.interventions.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.academic.GroupInfo;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.AuthenticatedUser;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.common.security.authorization.AccessAction;
import co.edu.retencion.common.security.authorization.ScopeAuthorizationService;
import co.edu.retencion.interventions.InterventionAssigned;
import co.edu.retencion.interventions.domain.Intervention;
import co.edu.retencion.interventions.domain.InterventionNote;
import co.edu.retencion.interventions.domain.InterventionNoteRepository;
import co.edu.retencion.interventions.domain.InterventionRepository;
import co.edu.retencion.interventions.domain.PlanActionMapping;
import co.edu.retencion.risk.RiskAnalysis;
import co.edu.retencion.risk.RiskQueries;
import co.edu.retencion.risk.SupportPlans;

/**
 * Intervenciones: alta, avance, resolución con reanálisis (antes/después), notas con visibilidad
 * por rol y conversión de un plan de apoyo aprobado en intervenciones.
 */
@Service
public class InterventionService {

    private final InterventionRepository interventions;
    private final InterventionNoteRepository notes;
    private final RiskQueries risk;
    private final RiskAnalysis analysis;
    private final SupportPlans plans;
    private final AcademicDirectory academic;
    private final ScopeAuthorizationService scope;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public InterventionService(InterventionRepository interventions, InterventionNoteRepository notes, RiskQueries risk,
                               RiskAnalysis analysis, SupportPlans plans, AcademicDirectory academic,
                               ScopeAuthorizationService scope, ApplicationEventPublisher events,
                               TransactionTemplate tx, Clock clock) {
        this.interventions = interventions;
        this.notes = notes;
        this.risk = risk;
        this.analysis = analysis;
        this.plans = plans;
        this.academic = academic;
        this.scope = scope;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    public record InterventionView(UUID id, UUID studentId, Intervention.Type type, Intervention.Status status,
                                   String title, String description, UUID responsibleUserId, String responsibleRole,
                                   LocalDate dueDate, String result, UUID alertId, UUID planId, UUID retentionCaseId,
                                   Intervention.RiskMark riskBefore, Intervention.RiskMark riskAfter,
                                   BigDecimal scoreChange, Instant createdAt, Instant resolvedAt) {

        static InterventionView of(Intervention i) {
            Intervention.RiskMark before = i.getRiskBeforeAssessment() == null ? null
                    : new Intervention.RiskMark(i.getRiskBeforeAssessment(), i.getRiskBeforeLevel(), i.getRiskBeforeScore());
            Intervention.RiskMark after = i.getRiskAfterAssessment() == null ? null
                    : new Intervention.RiskMark(i.getRiskAfterAssessment(), i.getRiskAfterLevel(), i.getRiskAfterScore());
            BigDecimal change = before != null && after != null && before.score() != null && after.score() != null
                    ? after.score().subtract(before.score()) : null;
            return new InterventionView(i.getId(), i.getStudentId(), i.getType(), i.getStatus(), i.getTitle(),
                    i.getDescription(), i.getResponsibleUserId(), i.getResponsibleRole(), i.getDueDate(), i.getResult(),
                    i.getAlertId(), i.getPlanId(), i.getRetentionCaseId(), before, after, change, i.getCreatedAt(),
                    i.getResolvedAt());
        }

        @Override
        public String toString() {
            return "InterventionView[" + id + "]";
        }
    }

    public record NoteView(UUID id, UUID authorId, boolean restricted, String text, Instant createdAt) {
        @Override
        public String toString() {
            return "NoteView[" + id + "]";
        }
    }

    /** Acción editada por el consejero al convertir un plan. */
    public record PlanActionInput(String type, String title, String description, String priority,
                                  String suggestedOwner, String resourceId, int dueInDays, UUID responsibleUserId) {
    }

    @Transactional
    public InterventionView create(Intervention.Data data) {
        AuthenticatedUser user = CurrentUser.require();
        scope.check("STUDENT", data.studentId(), AccessAction.WRITE);
        Intervention saved = interventions.save(Intervention.create(data, before(data.studentId()), user.userId(),
                clock.instant()));
        announce(saved);
        return InterventionView.of(saved);
    }

    @Transactional(readOnly = true)
    public List<InterventionView> ofStudent(UUID studentId) {
        return interventions.findByStudentIdOrderByCreatedAtDesc(studentId).stream().map(InterventionView::of).toList();
    }

    @Transactional(readOnly = true)
    public List<InterventionView> mine() {
        return interventions.findByResponsibleUserIdOrderByCreatedAtDesc(CurrentUser.require().userId()).stream()
                .map(InterventionView::of).toList();
    }

    @Transactional
    public InterventionView start(UUID id) {
        Intervention i = loadForWrite(id);
        i.start(clock.instant());
        return InterventionView.of(interventions.save(i));
    }

    /**
     * Resuelve y reanaliza el riesgo para medir el efecto. El reanálisis corre fuera de la transacción
     * de la resolución (puede llamar a la IA).
     */
    public InterventionView resolve(UUID id, String result) {
        AuthenticatedUser user = CurrentUser.require();
        Intervention resolved = tx.execute(s -> {
            Intervention i = loadForWrite(id);
            i.resolve(result, clock.instant());
            return interventions.save(i);
        });
        RiskQueries.LatestRisk after = analysis.reanalyze(resolved.getStudentId(), user.userId());
        return tx.execute(s -> {
            Intervention i = interventions.findById(id).orElseThrow();
            i.measured(new Intervention.RiskMark(after.assessmentId(), after.level().name(), after.score()),
                    clock.instant());
            return InterventionView.of(interventions.save(i));
        });
    }

    @Transactional
    public NoteView addNote(UUID id, String text, boolean restrictedRequested) {
        AuthenticatedUser user = CurrentUser.require();
        Intervention i = interventions.findById(id).orElseThrow(() -> DomainException.notFound("Intervención no encontrada"));
        requireAccess(i, user, AccessAction.WRITE);
        boolean restricted = i.notesAlwaysRestricted() || (restrictedRequested && user.hasRole(Role.PSICOLOGO));
        if (restricted && !user.hasRole(Role.PSICOLOGO)) {
            throw new AccessDeniedException("Las notas de psicología solo las escribe PSICOLOGO");
        }
        InterventionNote note = notes.save(InterventionNote.write(id, user.userId(), restricted, text, clock.instant()));
        return new NoteView(note.getId(), note.getAuthorId(), note.isRestricted(), note.getText(), note.getCreatedAt());
    }

    /** Las notas restringidas (psicología) solo las ve PSICOLOGO. */
    @Transactional(readOnly = true)
    public List<NoteView> notes(UUID id) {
        AuthenticatedUser user = CurrentUser.require();
        Intervention i = interventions.findById(id).orElseThrow(() -> DomainException.notFound("Intervención no encontrada"));
        requireAccess(i, user, AccessAction.READ);
        boolean psychologist = user.hasRole(Role.PSICOLOGO);
        return notes.findByInterventionIdOrderByCreatedAtAsc(id).stream().filter(n -> psychologist || !n.isRestricted())
                .map(n -> new NoteView(n.getId(), n.getAuthorId(), n.isRestricted(), n.getText(), n.getCreatedAt()))
                .toList();
    }

    /**
     * El consejero revisa el plan, lo edita, lo aprueba y lo convierte en intervenciones (una por
     * acción). El responsable sugerido DOCENTE es el docente del grupo; CONSEJERO, quien aprueba.
     */
    @Transactional
    public List<InterventionView> convertPlan(UUID planId, List<PlanActionInput> actions, String comment) {
        AuthenticatedUser user = CurrentUser.require();
        SupportPlans.PlanSummary plan = plans.find(planId).orElseThrow(() -> DomainException.notFound("Plan no encontrado"));
        scope.check("STUDENT", plan.studentId(), AccessAction.WRITE);
        if (!interventions.findByPlanId(planId).isEmpty()) {
            throw DomainException.conflict("El plan ya se convirtió en intervenciones");
        }
        SupportPlans.PlanSummary approved = plans.approveWithActions(planId, actions.stream()
                .map(a -> new SupportPlans.PlanItem(a.type(), a.title(), a.description(), a.priority(), a.suggestedOwner(),
                        a.resourceId(), a.dueInDays())).toList(), comment, user.userId());
        UUID teacher = academic.findGroup(approved.groupId()).map(GroupInfo::teacherUserId).orElse(null);
        Intervention.RiskMark before = before(approved.studentId());
        LocalDate today = LocalDate.now(clock.withZone(co.edu.retencion.common.time.TimeConfig.BUSINESS_ZONE));
        List<InterventionView> created = new ArrayList<>();
        for (PlanActionInput a : actions) {
            UUID responsible = a.responsibleUserId() != null ? a.responsibleUserId()
                    : "DOCENTE".equals(a.suggestedOwner()) ? teacher
                    : "CONSEJERO".equals(a.suggestedOwner()) ? user.userId() : null;
            Intervention saved = interventions.save(Intervention.create(new Intervention.Data(approved.studentId(),
                    PlanActionMapping.typeOf(a.type()), a.title(), a.description(), responsible, a.suggestedOwner(),
                    today.plusDays(Math.max(0, a.dueInDays())), null, planId, null), before, user.userId(),
                    clock.instant()));
            announce(saved);
            created.add(InterventionView.of(saved));
        }
        return created;
    }

    private Intervention.RiskMark before(UUID studentId) {
        return risk.latest(studentId).map(r -> new Intervention.RiskMark(r.assessmentId(), r.level().name(), r.score()))
                .orElse(null);
    }

    private void announce(Intervention i) {
        if (i.getResponsibleUserId() != null) {
            events.publishEvent(new InterventionAssigned(i.getId(), i.getStudentId(), i.getResponsibleUserId(),
                    i.getType().name()));
        }
    }

    private Intervention loadForWrite(UUID id) {
        Intervention i = interventions.findById(id).orElseThrow(() -> DomainException.notFound("Intervención no encontrada"));
        requireAccess(i, CurrentUser.require(), AccessAction.WRITE);
        return i;
    }

    private void requireAccess(Intervention i, AuthenticatedUser user, AccessAction action) {
        boolean responsible = user.userId().equals(i.getResponsibleUserId());
        if (!responsible && !scope.canAccess("STUDENT", i.getStudentId(), action)) {
            throw DomainException.notFound("Intervención no encontrada");
        }
    }

    Optional<Intervention> find(UUID id) {
        return interventions.findById(id);
    }
}
