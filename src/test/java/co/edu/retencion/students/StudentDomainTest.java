package co.edu.retencion.students;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.students.application.StudentViews.EnrollmentView;
import co.edu.retencion.students.application.StudentViews.StudentView;
import co.edu.retencion.students.domain.AnonCodeGenerator;
import co.edu.retencion.students.domain.DataConsent;
import co.edu.retencion.students.domain.Enrollment;
import co.edu.retencion.students.domain.SocioeconomicProfile;
import co.edu.retencion.students.domain.Student;
import co.edu.retencion.students.domain.StudentIdentity;

/** Dominio de estudiantes y garantía de que los DTO públicos no exponen identidad. */
class StudentDomainTest {

    private static final Instant NOW = Instant.parse("2026-11-03T15:00:00Z");
    private static final List<String> IDENTITY_WORDS = List.of("name", "nombre", "document", "documento", "email",
            "correo", "phone", "telefono", "mail", "cedula");

    @Test
    void publicStudentDtosNeverExposeIdentity() {
        for (Class<?> dto : List.of(StudentView.class, StudentInfo.class, EnrollmentView.class)) {
            for (RecordComponent component : dto.getRecordComponents()) {
                String name = component.getName().toLowerCase(Locale.ROOT);
                assertThat(IDENTITY_WORDS).as(dto.getSimpleName() + "." + component.getName())
                        .noneMatch(name::contains);
            }
        }
        assertThat(Arrays.stream(StudentView.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("id", "anonCode", "programId", "currentSemester", "status", "admissionDate",
                        "linkedToUser", "createdAt");
    }

    @Test
    void anonCodesAreRandomAndWellFormed() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            String code = AnonCodeGenerator.next();
            assertThat(AnonCodeGenerator.isValid(code)).isTrue();
            codes.add(code);
        }
        assertThat(codes).hasSize(20_000);
        assertThat(AnonCodeGenerator.isValid("EST-1020304050")).as("formato válido").isTrue();
        assertThat(AnonCodeGenerator.isValid("EST-ILOU000000")).as("sin letras ambiguas").isFalse();
    }

    @Test
    void studentLifecycle() {
        UUID program = UUID.randomUUID();
        Student student = Student.register("EST-0000000001", program, 1, StudentStatus.ACTIVO, LocalDate.of(2026, 2, 1), NOW);
        assertThat(student.updateAcademic(program, 1, StudentStatus.ACTIVO, LocalDate.of(2026, 2, 1), NOW)).isFalse();
        assertThat(student.updateAcademic(program, 2, StudentStatus.APLAZADO, LocalDate.of(2026, 2, 1), NOW)).isTrue();
        assertThatThrownBy(() -> student.updateAcademic(program, 15, StudentStatus.ACTIVO, LocalDate.now(), NOW))
                .isInstanceOf(DomainException.class);
        UUID user = UUID.randomUUID();
        student.linkUser(user, NOW);
        assertThat(student.isLinkedTo(user)).isTrue();
        assertThatThrownBy(() -> student.linkUser(UUID.randomUUID(), NOW)).isInstanceOf(DomainException.class);
        student.softDelete(NOW);
        assertThat(student.getDeletedAt()).isEqualTo(NOW);
    }

    @Test
    void identityChangeDetection() {
        StudentIdentity.Data data = new StudentIdentity.Data("Ana", DocumentType.CC, "123", "idx", null, null, null);
        StudentIdentity identity = StudentIdentity.of(UUID.randomUUID(), data, NOW);
        assertThat(identity.update(data, NOW)).isFalse();
        assertThat(identity.update(new StudentIdentity.Data("Ana María", DocumentType.CC, "123", "idx", null, null, null),
                NOW)).isTrue();
        assertThat(identity.toString()).doesNotContain("Ana");
        assertThat(data.toString()).doesNotContain("Ana");
    }

    @Test
    void socioeconomicValidation() {
        SocioeconomicProfile profile = SocioeconomicProfile.of(UUID.randomUUID(), 3, false, 0, TransportMode.A_PIE, NOW);
        assertThat(profile.update(3, false, 0, TransportMode.A_PIE, NOW)).isFalse();
        assertThat(profile.update(2, true, 1, TransportMode.MOTO, NOW)).isTrue();
        assertThatThrownBy(() -> profile.update(7, null, null, null, NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> profile.update(null, null, 30, null, NOW)).isInstanceOf(DomainException.class);
    }

    @Test
    void enrollmentAndConsent() {
        Enrollment enrollment = Enrollment.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), NOW);
        assertThat(enrollment.isActive()).isTrue();
        enrollment.withdraw(NOW);
        assertThat(enrollment.isActive()).isFalse();
        assertThat(enrollment.getWithdrawnAt()).isEqualTo(NOW);
        enrollment.reactivate(NOW);
        assertThat(enrollment.isActive()).isTrue();

        DataConsent consent = DataConsent.record(UUID.randomUUID(), "2026.1", DataConsent.Purpose.ANALITICA_RETENCION,
                false, DataConsent.Channel.PORTAL, null, NOW);
        assertThat(consent.isGranted()).isFalse();
        assertThat(consent.getPurpose()).isEqualTo(DataConsent.Purpose.ANALITICA_RETENCION);
    }
}
