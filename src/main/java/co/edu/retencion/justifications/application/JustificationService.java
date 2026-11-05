package co.edu.retencion.justifications.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.academic.GroupInfo;
import co.edu.retencion.academic.SubjectInfo;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.AuthenticatedUser;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.common.time.TimeConfig;
import co.edu.retencion.justifications.JustificationDecided;
import co.edu.retencion.justifications.application.JustificationViews.DecisionView;
import co.edu.retencion.justifications.application.JustificationViews.JustificationView;
import co.edu.retencion.justifications.domain.AbsenceJustification;
import co.edu.retencion.justifications.domain.AbsenceJustificationRepository;
import co.edu.retencion.justifications.domain.JustificationEvidence;
import co.edu.retencion.justifications.domain.JustificationEvidenceRepository;
import co.edu.retencion.records.AbsenceInfo;
import co.edu.retencion.records.AttendanceLedger;
import co.edu.retencion.records.CounterInfo;
import co.edu.retencion.students.StudentRegistry;

/**
 * Flujo de justificación: el estudiante la envía dentro del plazo; el docente de la materia (y
 * solo él) la aprueba o rechaza. Ningún otro rol decide y no hay aprobación automática.
 */
@Service
public class JustificationService {

    private final AbsenceJustificationRepository justifications;
    private final JustificationEvidenceRepository evidences;
    private final AttendanceLedger ledger;
    private final AcademicDirectory academic;
    private final StudentRegistry students;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public JustificationService(AbsenceJustificationRepository justifications, JustificationEvidenceRepository evidences,
                                AttendanceLedger ledger, AcademicDirectory academic, StudentRegistry students,
                                ApplicationEventPublisher events, Clock clock) {
        this.justifications = justifications;
        this.evidences = evidences;
        this.ledger = ledger;
        this.academic = academic;
        this.students = students;
        this.events = events;
        this.clock = clock;
    }

    /** El estudiante justifica una de SUS faltas. */
    @Transactional
    public JustificationView submit(UUID attendanceRecordId, AbsenceJustification.Category category, String description) {
        UUID studentId = currentStudentId();
        AbsenceInfo absence = ledger.findRecord(attendanceRecordId)
                .filter(a -> a.studentId().equals(studentId) && a.isAbsence())
                .orElseThrow(() -> DomainException.notFound("Falta no encontrada"));
        ledger.markJustificationPending(attendanceRecordId);
        UUID teacher = academic.findGroup(absence.groupId()).map(GroupInfo::teacherUserId).orElse(null);
        AbsenceJustification justification = justifications.save(AbsenceJustification.submit(absence.recordId(),
                absence.termId(), absence.enrollmentId(), studentId, absence.groupId(), category, description,
                absence.justificationDeadline(), teacher, clock.instant()));
        events.publishEvent(new co.edu.retencion.justifications.JustificationSubmitted(justification.getId(), studentId,
                absence.groupId(), teacher));
        return JustificationView.of(justification, List.of());
    }

    @Transactional(readOnly = true)
    public List<JustificationView> mine() {
        UUID studentId = currentStudentId();
        return withEvidences(justifications.findByStudentIdOrderBySubmittedAtDesc(studentId));
    }

    /** Bandeja del docente: pendientes de sus grupos. */
    @Transactional(readOnly = true)
    public List<JustificationView> pendingForCurrentTeacher() {
        return withEvidences(justifications.findByAssignedTeacherIdAndStatusOrderBySubmittedAtAsc(
                CurrentUser.require().userId(), AbsenceJustification.Status.PENDIENTE));
    }

    /** Detalle: estudiante titular, docente asignado o ADMIN. */
    @Transactional(readOnly = true)
    public JustificationView get(UUID id) {
        AbsenceJustification justification = load(id);
        requireCanView(justification, CurrentUser.require());
        return JustificationView.of(justification, evidences.findByJustificationIdOrderByUploadedAtAsc(id));
    }

    /**
     * Decisión del docente. Verifica que quien decide sea el docente actual del grupo y el asignado;
     * otro docente, coordinación o administración reciben 403.
     */
    @Transactional
    public DecisionView decide(UUID id, boolean approved, String comment) {
        AuthenticatedUser user = CurrentUser.require();
        AbsenceJustification justification = load(id);
        UUID currentTeacher = academic.findGroup(justification.getGroupId()).map(GroupInfo::teacherUserId).orElse(null);
        if (!user.hasRole(Role.DOCENTE) || !user.userId().equals(currentTeacher)
                || !justification.isAssignedTo(user.userId())) {
            throw new AccessDeniedException("Solo el docente de la materia puede decidir esta justificación");
        }
        boolean scanPending = evidences.findByJustificationIdOrderByUploadedAtAsc(id).stream()
                .anyMatch(e -> e.getScanStatus() == JustificationEvidence.ScanStatus.PENDIENTE
                        || e.getScanStatus() == JustificationEvidence.ScanStatus.ERROR);
        if (scanPending) {
            throw new DomainException(JustificationProblems.EVIDENCE_SCAN_PENDING, "Hay evidencias en revisión antivirus");
        }
        justification.decide(approved, user.userId(), comment, clock.instant());
        justifications.saveAndFlush(justification);
        CounterInfo counter = ledger.resolveJustification(justification.getAttendanceRecordId(), approved);

        GroupInfo group = academic.findGroup(justification.getGroupId()).orElseThrow();
        String subjectName = academic.findSubject(group.subjectId()).map(SubjectInfo::name).orElse("");
        LocalDate sessionDate = ledger.findRecord(justification.getAttendanceRecordId())
                .map(AbsenceInfo::sessionDate).orElse(null);
        events.publishEvent(new JustificationDecided(id, justification.getAttendanceRecordId(),
                justification.getEnrollmentId(), justification.getStudentId(), justification.getGroupId(), approved,
                subjectName, sessionDate));
        return new DecisionView(JustificationView.of(justification,
                evidences.findByJustificationIdOrderByUploadedAtAsc(id)), counter);
    }

    /** Si el grupo cambia de docente, sus justificaciones pendientes pasan al nuevo docente. */
    @Transactional
    public int reassignPending(UUID groupId, UUID newTeacher) {
        List<AbsenceJustification> pending = justifications.findByGroupIdAndStatus(groupId,
                AbsenceJustification.Status.PENDIENTE);
        pending.forEach(j -> j.reassign(newTeacher, clock.instant()));
        return pending.size();
    }

    /** Job de vencimiento: faltas no justificadas dentro del plazo quedan como VENCIDAS. */
    @Transactional
    public int expireOverdue() {
        return ledger.expireOverdue(LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE)));
    }

    AbsenceJustification load(UUID id) {
        return justifications.findById(id).orElseThrow(() -> DomainException.notFound("Justificación no encontrada"));
    }

    void requireCanView(AbsenceJustification justification, AuthenticatedUser user) {
        boolean owner = students.findStudentIdByUser(user.userId()).map(justification.getStudentId()::equals).orElse(false);
        boolean reviewer = user.hasRole(Role.DOCENTE) && justification.isAssignedTo(user.userId());
        if (!owner && !reviewer && !user.hasRole(Role.ADMIN)) {
            throw new AccessDeniedException("Sin acceso a la justificación");
        }
    }

    UUID currentStudentId() {
        return students.findStudentIdByUser(CurrentUser.require().userId())
                .orElseThrow(() -> new AccessDeniedException("La cuenta no está vinculada a un estudiante"));
    }

    private List<JustificationView> withEvidences(List<AbsenceJustification> rows) {
        Map<UUID, List<JustificationEvidence>> byJustification = evidences.findByJustificationIdIn(
                rows.stream().map(AbsenceJustification::getId).toList()).stream()
                .collect(Collectors.groupingBy(JustificationEvidence::getJustificationId));
        return rows.stream().map(j -> JustificationView.of(j, byJustification.getOrDefault(j.getId(), List.of()))).toList();
    }
}
