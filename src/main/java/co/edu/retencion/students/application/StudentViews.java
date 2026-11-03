package co.edu.retencion.students.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import co.edu.retencion.students.DocumentType;
import co.edu.retencion.students.StudentStatus;
import co.edu.retencion.students.TransportMode;
import co.edu.retencion.students.domain.DataConsent;
import co.edu.retencion.students.domain.Enrollment;
import co.edu.retencion.students.domain.SocioeconomicProfile;
import co.edu.retencion.students.domain.Student;
import co.edu.retencion.students.domain.StudentIdentity;

/**
 * DTOs del módulo de estudiantes. {@link StudentView} es el DTO público: NO contiene identidad
 * (una prueba lo verifica). La identidad solo viaja en {@link StudentIdentityView}, que se entrega
 * a roles autorizados y siempre queda auditada.
 */
public final class StudentViews {

    private StudentViews() {
    }

    /** DTO público, seudonimizado. */
    public record StudentView(UUID id, String anonCode, UUID programId, int currentSemester, StudentStatus status,
                              LocalDate admissionDate, boolean linkedToUser, Instant createdAt) {
        static StudentView of(Student s) {
            return new StudentView(s.getId(), s.getAnonCode(), s.getProgramId(), s.getCurrentSemester(), s.getStatus(),
                    s.getAdmissionDate(), s.getUserId() != null, s.getCreatedAt());
        }
    }

    /** Identidad (datos personales). Solo roles autorizados, con auditoría. */
    public record StudentIdentityView(UUID studentId, String anonCode, String fullName, DocumentType documentType,
                                      String documentNumber, String email, String phone) {
        static StudentIdentityView of(Student s, StudentIdentity i) {
            return new StudentIdentityView(s.getId(), s.getAnonCode(), i.getFullName(), i.getDocumentType(),
                    i.getDocumentNumber(), i.getEmail(), i.getPhone());
        }

        @Override
        public String toString() {
            return "StudentIdentityView[studentId=" + studentId + "]";
        }
    }

    /** Lo que el estudiante ve de sí mismo en el portal. */
    public record StudentSelfView(StudentView student, StudentIdentityView identity) {
    }

    public record SocioeconomicView(UUID studentId, Integer stratum, Boolean works, Integer dependents,
                                    TransportMode transportMode, Instant updatedAt) {
        static SocioeconomicView of(SocioeconomicProfile p) {
            return new SocioeconomicView(p.getStudentId(), p.getStratum(), p.getWorks(), p.getDependents(),
                    p.getTransportMode(), p.getUpdatedAt());
        }
    }

    public record EnrollmentView(UUID id, UUID studentId, UUID groupId, UUID termId, Enrollment.Status status,
                                 Instant enrolledAt, Instant withdrawnAt) {
        static EnrollmentView of(Enrollment e) {
            return new EnrollmentView(e.getId(), e.getStudentId(), e.getGroupId(), e.getTermId(), e.getStatus(),
                    e.getEnrolledAt(), e.getWithdrawnAt());
        }
    }

    /** Fila de la lista de clase: el docente ve el nombre de sus estudiantes (auditado). */
    public record RosterEntry(UUID studentId, String anonCode, String fullName, StudentStatus studentStatus) {
    }

    public record ConsentView(UUID id, String policyVersion, DataConsent.Purpose purpose, boolean granted,
                              DataConsent.Channel channel, Instant recordedAt) {
        static ConsentView of(DataConsent c) {
            return new ConsentView(c.getId(), c.getPolicyVersion(), c.getPurpose(), c.isGranted(), c.getChannel(),
                    c.getRecordedAt());
        }
    }
}
