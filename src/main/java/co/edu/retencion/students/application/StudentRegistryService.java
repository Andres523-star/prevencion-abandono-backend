package co.edu.retencion.students.application;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Limit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.common.data.UpsertOutcome;
import co.edu.retencion.students.EnrollmentInfo;
import co.edu.retencion.students.SocioeconomicInfo;
import co.edu.retencion.students.StudentInfo;
import co.edu.retencion.students.StudentRegistry;
import co.edu.retencion.students.domain.Enrollment;
import co.edu.retencion.students.domain.EnrollmentRepository;
import co.edu.retencion.students.domain.SocioeconomicProfileRepository;
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
    private final SocioeconomicProfileRepository socioeconomic;
    private final co.edu.retencion.students.domain.DataConsentRepository consents;
    private final java.time.Clock clock;

    public StudentRegistryService(StudentRepository students, StudentIdentityRepository identities,
                                  StudentWriter writer, EnrollmentService enrollments,
                                  EnrollmentRepository enrollmentRepository,
                                  SocioeconomicProfileRepository socioeconomic, java.time.Clock clock,
                                  co.edu.retencion.students.domain.DataConsentRepository consents) {
        this.consents = consents;
        this.socioeconomic = socioeconomic;
        this.clock = clock;
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
    public Optional<IdentityRecord> identityOf(UUID studentId) {
        return identities.findById(studentId).map(i -> new IdentityRecord(i.getFullName(), i.getDocumentType().name(),
                i.getDocumentNumber(), i.getEmail(), i.getPhone()));
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<ConsentRecord> consentsOf(UUID studentId) {
        return consents.findByStudentIdOrderByRecordedAtDesc(studentId).stream()
                .map(c -> new ConsentRecord(c.getPurpose().name(), c.isGranted(), c.getPolicyVersion(), c.getChannel().name(),
                        c.getRecordedAt())).toList();
    }

    @Override
    @Transactional
    public Optional<UUID> suppressPersonalData(UUID studentId) {
        Student student = students.findById(studentId)
                .orElseThrow(() -> co.edu.retencion.common.error.DomainException.notFound("Estudiante no encontrado"));
        UUID user = student.getUserId();
        identities.findById(studentId).ifPresent(identities::delete);
        socioeconomic.findById(studentId).ifPresent(socioeconomic::delete);
        student.unlinkUser(clock.instant());
        student.softDelete(clock.instant());
        students.saveAndFlush(student);
        return Optional.ofNullable(user);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Map<UUID, StudentInfo> findByIds(java.util.Collection<UUID> studentIds) {
        return students.findAllById(studentIds).stream().map(StudentRegistryService::info)
                .collect(java.util.stream.Collectors.toMap(StudentInfo::id, java.util.function.Function.identity()));
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Map<UUID, String> displayNames(java.util.Collection<UUID> studentIds) {
        return identities.findAllById(studentIds).stream().collect(java.util.stream.Collectors.toMap(
                StudentIdentity::getStudentId, i -> i.getFullName().trim()));
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

    @Override
    @Transactional(readOnly = true)
    public List<EnrollmentInfo> enrollmentsOf(UUID studentId) {
        return enrollmentRepository.findByStudentIdOrderByEnrolledAtDesc(studentId).stream()
                .map(StudentRegistryService::info).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> studentIdsEnrolledInTerm(UUID termId) {
        return enrollmentRepository.findStudentIdsByTermId(termId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> activeStudentIds(UUID programId, UUID after, int limit) {
        return students.activeIds(programId, after == null, after, Limit.of(Math.max(1, Math.min(limit, 1000))));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SocioeconomicInfo> socioeconomic(UUID studentId) {
        return socioeconomic.findById(studentId).map(p -> new SocioeconomicInfo(p.getStratum(), p.getWorks(),
                p.getDependents(), p.getTransportMode()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> givenName(UUID studentId) {
        return identities.findById(studentId).map(StudentIdentity::getFullName)
                .map(String::trim).filter(n -> !n.isEmpty()).map(n -> n.split("\\s+")[0]);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> displayName(UUID studentId) {
        return identities.findById(studentId).map(StudentIdentity::getFullName).map(String::trim);
    }

    @Override
    @Transactional
    public void changeStatus(UUID studentId, co.edu.retencion.students.StudentStatus status) {
        Student student = students.findById(studentId)
                .orElseThrow(() -> co.edu.retencion.common.error.DomainException.notFound("Estudiante no encontrado"));
        student.updateAcademic(student.getProgramId(), student.getCurrentSemester(), status, student.getAdmissionDate(),
                clock.instant());
        students.saveAndFlush(student);
    }

    static EnrollmentInfo info(Enrollment e) {
        return new EnrollmentInfo(e.getId(), e.getStudentId(), e.getGroupId(), e.getTermId(), e.isActive());
    }

    static StudentInfo info(Student s) {
        return new StudentInfo(s.getId(), s.getAnonCode(), s.getProgramId(), s.getCurrentSemester(), s.getStatus(),
                s.getUserId());
    }
}
