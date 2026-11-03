package co.edu.retencion.students.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.academic.GroupInfo;
import co.edu.retencion.common.data.UpsertOutcome;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.students.StudentStatus;
import co.edu.retencion.students.application.StudentViews.EnrollmentView;
import co.edu.retencion.students.application.StudentViews.RosterEntry;
import co.edu.retencion.students.domain.Enrollment;
import co.edu.retencion.students.domain.EnrollmentRepository;
import co.edu.retencion.students.domain.Student;
import co.edu.retencion.students.domain.StudentIdentity;
import co.edu.retencion.students.domain.StudentIdentityRepository;
import co.edu.retencion.students.domain.StudentRepository;

/** Inscripciones a grupos, retiros y lista de clase. */
@Service
public class EnrollmentService {

    private final EnrollmentRepository enrollments;
    private final StudentRepository students;
    private final StudentIdentityRepository identities;
    private final AcademicDirectory academic;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public EnrollmentService(EnrollmentRepository enrollments, StudentRepository students,
                             StudentIdentityRepository identities, AcademicDirectory academic, JdbcTemplate jdbc,
                             Clock clock) {
        this.enrollments = enrollments;
        this.students = students;
        this.identities = identities;
        this.academic = academic;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public EnrollmentView enroll(UUID studentId, UUID groupId) {
        enrollInternal(studentId, groupId);
        return EnrollmentView.of(enrollments.findByStudentIdAndGroupId(studentId, groupId).orElseThrow());
    }

    /**
     * Inscribe respetando el cupo. Un bloqueo transaccional por grupo evita que dos inscripciones
     * simultáneas superen el cupo.
     */
    @Transactional
    public UpsertOutcome enrollInternal(UUID studentId, UUID groupId) {
        Student student = students.findById(studentId)
                .orElseThrow(() -> DomainException.notFound("Estudiante no encontrado"));
        if (student.getStatus() != StudentStatus.ACTIVO) {
            throw DomainException.conflict("Solo se inscriben estudiantes activos");
        }
        GroupInfo group = academic.findGroup(groupId).orElseThrow(() -> DomainException.notFound("Grupo no encontrado"));
        Optional<Enrollment> existing = enrollments.findByStudentIdAndGroupId(studentId, groupId);
        if (existing.isPresent() && existing.get().isActive()) {
            return UpsertOutcome.UNCHANGED;
        }
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null, "group:" + groupId);
        if (enrollments.countByGroupIdAndStatus(groupId, Enrollment.Status.ACTIVA) >= group.capacity()) {
            throw DomainException.conflict("El grupo no tiene cupo disponible");
        }
        if (existing.isPresent()) {
            existing.get().reactivate(clock.instant());
            return UpsertOutcome.UPDATED;
        }
        enrollments.save(Enrollment.of(studentId, groupId, group.termId(), clock.instant()));
        return UpsertOutcome.CREATED;
    }

    @Transactional
    public EnrollmentView withdraw(UUID enrollmentId) {
        Enrollment enrollment = load(enrollmentId);
        if (!enrollment.isActive()) {
            throw DomainException.conflict("La inscripción ya estaba retirada");
        }
        enrollment.withdraw(clock.instant());
        return EnrollmentView.of(enrollments.saveAndFlush(enrollment));
    }

    @Transactional(readOnly = true)
    public List<EnrollmentView> ofStudent(UUID studentId, UUID termId) {
        List<Enrollment> rows = termId == null
                ? enrollments.findByStudentIdOrderByEnrolledAtDesc(studentId)
                : enrollments.findByStudentIdAndTermIdOrderByEnrolledAtDesc(studentId, termId);
        return rows.stream().map(EnrollmentView::of).toList();
    }

    /** Lista de clase con nombres (lectura de identidad: el controlador la audita). */
    @Transactional(readOnly = true)
    public List<RosterEntry> roster(UUID groupId) {
        List<Enrollment> active = enrollments.findByGroupIdAndStatus(groupId, Enrollment.Status.ACTIVA);
        List<UUID> ids = active.stream().map(Enrollment::getStudentId).toList();
        Map<UUID, StudentIdentity> byStudent = identities.findByStudentIdIn(ids).stream()
                .collect(Collectors.toMap(StudentIdentity::getStudentId, Function.identity()));
        return students.findAllById(ids).stream()
                .map(s -> new RosterEntry(s.getId(), s.getAnonCode(),
                        Optional.ofNullable(byStudent.get(s.getId())).map(StudentIdentity::getFullName).orElse(null),
                        s.getStatus()))
                .sorted(java.util.Comparator.comparing(RosterEntry::fullName,
                        java.util.Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    Enrollment load(UUID id) {
        return enrollments.findById(id).orElseThrow(() -> DomainException.notFound("Inscripción no encontrada"));
    }
}
