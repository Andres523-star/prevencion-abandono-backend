package co.edu.retencion.students.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.common.data.UpsertOutcome;
import co.edu.retencion.students.EnrollmentInfo;
import co.edu.retencion.students.StudentInfo;
import co.edu.retencion.students.StudentRegistry;
import co.edu.retencion.students.domain.Enrollment;
import co.edu.retencion.students.domain.EnrollmentRepository;
import co.edu.retencion.students.domain.Student;
import co.edu.retencion.students.domain.StudentIdentity;
import co.edu.retencion.students.domain.StudentIdentityRepository;
import co.edu.retencion.students.domain.StudentRepository;

/** Implementación de la API pública {@link StudentRegistry}. */
@Service
public class StudentRegistryService implements StudentRegistry {

    private final StudentRepository students;
    private final StudentIdentityRepository identities;
    private final StudentWriter writer;
    private final EnrollmentService enrollments;
    private final EnrollmentRepository enrollmentRepository;

    public StudentRegistryService(StudentRepository students, StudentIdentityRepository identities,
                                  StudentWriter writer, EnrollmentService enrollments,
                                  EnrollmentRepository enrollmentRepository) {
        this.students = students;
        this.identities = identities;
        this.writer = writer;
        this.enrollments = enrollments;
        this.enrollmentRepository = enrollmentRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StudentInfo> findById(UUID studentId) {
        return students.findById(studentId).map(StudentRegistryService::info);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findIdByDocument(String documentNumber) {
        return identities.findByDocumentBlindIndex(writer.documentIndex(documentNumber))
                .map(StudentIdentity::getStudentId);
    }

    @Override
    @Transactional
    public UpsertOutcome upsert(StudentRecord record, UUID actor) {
        return writer.upsert(record, actor, false).outcome();
    }

    @Override
    @Transactional
    public UpsertOutcome enroll(UUID studentId, UUID groupId) {
        return enrollments.enrollInternal(studentId, groupId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EnrollmentInfo> findEnrollment(UUID enrollmentId) {
        return enrollmentRepository.findById(enrollmentId).map(StudentRegistryService::info);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EnrollmentInfo> findEnrollment(UUID studentId, UUID groupId) {
        return enrollmentRepository.findByStudentIdAndGroupId(studentId, groupId).map(StudentRegistryService::info);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EnrollmentInfo> activeEnrollments(UUID groupId) {
        return enrollmentRepository.findByGroupIdAndStatus(groupId, Enrollment.Status.ACTIVA).stream()
                .map(StudentRegistryService::info).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findStudentIdByUser(UUID userId) {
        return students.findByUserId(userId).map(Student::getId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Contact> notificationContact(UUID studentId) {
        return students.findById(studentId).flatMap(s -> identities.findById(studentId))
                .filter(i -> i.getEmail() != null)
                .map(i -> new Contact(i.getEmail(), i.getEmailBlindIndex()));
    }

    static EnrollmentInfo info(Enrollment e) {
        return new EnrollmentInfo(e.getId(), e.getStudentId(), e.getGroupId(), e.getTermId(), e.isActive());
    }

    static StudentInfo info(Student s) {
        return new StudentInfo(s.getId(), s.getAnonCode(), s.getProgramId(), s.getCurrentSemester(), s.getStatus(),
                s.getUserId());
    }
}
