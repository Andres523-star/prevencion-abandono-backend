package co.edu.retencion.students.application;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.common.data.UpsertOutcome;
import co.edu.retencion.students.StudentInfo;
import co.edu.retencion.students.StudentRegistry;
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

    public StudentRegistryService(StudentRepository students, StudentIdentityRepository identities,
                                  StudentWriter writer, EnrollmentService enrollments) {
        this.students = students;
        this.identities = identities;
        this.writer = writer;
        this.enrollments = enrollments;
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

    static StudentInfo info(Student s) {
        return new StudentInfo(s.getId(), s.getAnonCode(), s.getProgramId(), s.getCurrentSemester(), s.getStatus(),
                s.getUserId());
    }
}
