package co.edu.retencion.academic.application;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.academic.EffectiveAttendancePolicy;
import co.edu.retencion.academic.application.AcademicViews.PolicyView;
import co.edu.retencion.academic.domain.AttendancePolicy;
import co.edu.retencion.academic.domain.AttendancePolicyRepository;
import co.edu.retencion.academic.domain.CourseGroup;
import co.edu.retencion.academic.domain.CourseGroupRepository;
import co.edu.retencion.academic.domain.PolicyValues;
import co.edu.retencion.academic.domain.Program;
import co.edu.retencion.academic.domain.Subject;
import co.edu.retencion.academic.domain.SubjectRepository;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.CurrentUser;

/**
 * Administración de políticas de asistencia y resolución de la política efectiva de un grupo.
 * Prioridad: materia &gt; práctica clínica del programa &gt; programa &gt; valores por defecto.
 */
@Service
public class AttendancePolicyService {

    private final AttendancePolicyRepository policies;
    private final CourseGroupRepository groups;
    private final SubjectRepository subjects;
    private final ProgramService programService;
    private final Clock clock;

    public AttendancePolicyService(AttendancePolicyRepository policies, CourseGroupRepository groups,
                                   SubjectRepository subjects, ProgramService programService, Clock clock) {
        this.policies = policies;
        this.groups = groups;
        this.subjects = subjects;
        this.programService = programService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public EffectiveAttendancePolicy effectiveForGroup(UUID groupId) {
        CourseGroup group = groups.findById(groupId).orElseThrow(() -> DomainException.notFound("Grupo no encontrado"));
        Subject subject = loadSubject(group.getSubjectId());
        return AttendancePolicy.resolve(
                policies.findBySubjectId(subject.getId()),
                policies.findByScopeAndProgramId(AttendancePolicy.Scope.PROGRAM_CLINICAL, subject.getProgramId()),
                policies.findByScopeAndProgramId(AttendancePolicy.Scope.PROGRAM, subject.getProgramId()),
                group.getGroupType());
    }

    @Transactional(readOnly = true)
    public Optional<PolicyView> programPolicy(UUID programId, boolean clinical) {
        programService.load(programId);
        return policies.findByScopeAndProgramId(scope(clinical), programId).map(PolicyView::of);
    }

    @Transactional
    public PolicyView putProgramPolicy(UUID programId, boolean clinical, PolicyValues values) {
        Program program = programService.load(programId);
        if (clinical && !program.isClinical()) {
            throw DomainException.badRequest("El programa " + program.getCode() + " no tiene prácticas clínicas");
        }
        UUID actor = CurrentUser.require().userId();
        AttendancePolicy policy = policies.findByScopeAndProgramId(scope(clinical), programId)
                .map(existing -> {
                    existing.apply(values, actor, clock.instant());
                    return existing;
                })
                .orElseGet(() -> AttendancePolicy.forProgram(programId, clinical, values, actor, clock.instant()));
        return PolicyView.of(policies.saveAndFlush(policy));
    }

    @Transactional(readOnly = true)
    public Optional<PolicyView> subjectPolicy(UUID subjectId) {
        loadSubject(subjectId);
        return policies.findBySubjectId(subjectId).map(PolicyView::of);
    }

    @Transactional
    public PolicyView putSubjectPolicy(UUID subjectId, PolicyValues values) {
        loadSubject(subjectId);
        UUID actor = CurrentUser.require().userId();
        AttendancePolicy policy = policies.findBySubjectId(subjectId)
                .map(existing -> {
                    existing.apply(values, actor, clock.instant());
                    return existing;
                })
                .orElseGet(() -> AttendancePolicy.forSubject(subjectId, values, actor, clock.instant()));
        return PolicyView.of(policies.saveAndFlush(policy));
    }

    /** Quita la excepción de la materia: vuelve a regir la política del programa. */
    @Transactional
    public void deleteSubjectPolicy(UUID subjectId) {
        AttendancePolicy policy = policies.findBySubjectId(subjectId)
                .orElseThrow(() -> DomainException.notFound("La materia no tiene política propia"));
        policies.delete(policy);
    }

    private Subject loadSubject(UUID id) {
        return subjects.findById(id).orElseThrow(() -> DomainException.notFound("Materia no encontrada"));
    }

    private static AttendancePolicy.Scope scope(boolean clinical) {
        return clinical ? AttendancePolicy.Scope.PROGRAM_CLINICAL : AttendancePolicy.Scope.PROGRAM;
    }
}
