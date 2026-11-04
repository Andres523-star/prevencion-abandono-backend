package co.edu.retencion.wellbeing.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.common.time.TimeConfig;
import co.edu.retencion.students.StudentRegistry;
import co.edu.retencion.wellbeing.WellbeingSignals;
import co.edu.retencion.wellbeing.domain.WellbeingAssessment;
import co.edu.retencion.wellbeing.domain.WellbeingAssessmentRepository;
import co.edu.retencion.wellbeing.domain.WellbeingReferral;
import co.edu.retencion.wellbeing.domain.WellbeingReferralRepository;

/**
 * Evaluaciones y remisiones de bienestar. La restricción al rol PSICOLOGO y la auditoría de lectura
 * se aplican en el controlador; los demás módulos solo ven {@link WellbeingSignals}.
 */
@Service
public class WellbeingService implements WellbeingSignals {

    private final WellbeingAssessmentRepository assessments;
    private final WellbeingReferralRepository referrals;
    private final StudentRegistry students;
    private final Clock clock;

    public WellbeingService(WellbeingAssessmentRepository assessments, WellbeingReferralRepository referrals,
                            StudentRegistry students, Clock clock) {
        this.assessments = assessments;
        this.referrals = referrals;
        this.students = students;
        this.clock = clock;
    }

    public record AssessmentView(UUID id, UUID studentId, WellbeingAssessment.Type type, String summary,
                                 String findings, UUID assessedBy, LocalDate assessedOn, Instant createdAt) {
        static AssessmentView of(WellbeingAssessment a) {
            return new AssessmentView(a.getId(), a.getStudentId(), a.getType(), a.getSummary(), a.getFindings(),
                    a.getAssessedBy(), a.getAssessedOn(), a.getCreatedAt());
        }

        @Override
        public String toString() {
            return "AssessmentView[id=" + id + "]";
        }
    }

    public record ReferralView(UUID id, UUID studentId, UUID assessmentId, WellbeingReferral.Service service,
                               String reason, WellbeingReferral.Status status, UUID createdBy, Instant createdAt,
                               Instant closedAt) {
        static ReferralView of(WellbeingReferral r) {
            return new ReferralView(r.getId(), r.getStudentId(), r.getAssessmentId(), r.getService(), r.getReason(),
                    r.getStatus(), r.getCreatedBy(), r.getCreatedAt(), r.getClosedAt());
        }

        @Override
        public String toString() {
            return "ReferralView[id=" + id + "]";
        }
    }

    @Transactional
    public AssessmentView record(UUID studentId, WellbeingAssessment.Type type, String summary, String findings,
                                 LocalDate assessedOn) {
        requireStudent(studentId);
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        LocalDate date = assessedOn == null ? today : assessedOn;
        if (date.isAfter(today)) {
            throw DomainException.badRequest("La fecha de evaluación no puede ser futura");
        }
        return AssessmentView.of(assessments.save(WellbeingAssessment.record(studentId, type, summary, findings,
                CurrentUser.require().userId(), date, clock.instant())));
    }

    @Transactional(readOnly = true)
    public List<AssessmentView> assessments(UUID studentId) {
        requireStudent(studentId);
        return assessments.findByStudentIdOrderByAssessedOnDesc(studentId).stream().map(AssessmentView::of).toList();
    }

    @Transactional
    public ReferralView refer(UUID studentId, UUID assessmentId, WellbeingReferral.Service service, String reason) {
        requireStudent(studentId);
        if (assessmentId != null && assessments.findById(assessmentId)
                .filter(a -> a.getStudentId().equals(studentId)).isEmpty()) {
            throw DomainException.badRequest("La evaluación no pertenece al estudiante");
        }
        return ReferralView.of(referrals.save(WellbeingReferral.open(studentId, assessmentId, service, reason,
                CurrentUser.require().userId(), clock.instant())));
    }

    @Transactional(readOnly = true)
    public List<ReferralView> referrals(UUID studentId) {
        requireStudent(studentId);
        return referrals.findByStudentIdOrderByCreatedAtDesc(studentId).stream().map(ReferralView::of).toList();
    }

    @Transactional
    public ReferralView moveReferral(UUID referralId, WellbeingReferral.Status status) {
        WellbeingReferral referral = referrals.findById(referralId)
                .orElseThrow(() -> DomainException.notFound("Remisión no encontrada"));
        referral.moveTo(status, clock.instant());
        return ReferralView.of(referral);
    }

    @Override
    @Transactional(readOnly = true)
    public Summary summary(UUID studentId) {
        return new Summary(assessments.countByStudentIdAndType(studentId, WellbeingAssessment.Type.PSICOLOGICA),
                assessments.countByStudentIdAndType(studentId, WellbeingAssessment.Type.FISICA),
                referrals.countByStudentIdAndStatus(studentId, WellbeingReferral.Status.ABIERTA));
    }

    private void requireStudent(UUID studentId) {
        if (students.findById(studentId).isEmpty()) {
            throw DomainException.notFound("Estudiante no encontrado");
        }
    }
}
