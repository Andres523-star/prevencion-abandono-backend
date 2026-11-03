package co.edu.retencion.students.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.common.crypto.BlindIndexer;
import co.edu.retencion.common.data.UpsertOutcome;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.students.StudentRegistry.StudentRecord;
import co.edu.retencion.students.domain.AnonCodeGenerator;
import co.edu.retencion.students.domain.DataConsent;
import co.edu.retencion.students.domain.DataConsentRepository;
import co.edu.retencion.students.domain.SocioeconomicProfile;
import co.edu.retencion.students.domain.SocioeconomicProfileRepository;
import co.edu.retencion.students.domain.Student;
import co.edu.retencion.students.domain.StudentIdentity;
import co.edu.retencion.students.domain.StudentIdentityRepository;
import co.edu.retencion.students.domain.StudentRepository;
import co.edu.retencion.users.UserDirectory;

/**
 * Núcleo de escritura de estudiantes, compartido por la API REST y la importación: normaliza la
 * identidad, calcula blind index, genera el anon_code y vincula la cuenta de usuario por correo.
 * Debe llamarse dentro de una transacción.
 */
@Component
class StudentWriter {

    private static final int ANON_CODE_ATTEMPTS = 5;

    private final StudentRepository students;
    private final StudentIdentityRepository identities;
    private final SocioeconomicProfileRepository profiles;
    private final DataConsentRepository consents;
    private final BlindIndexer blindIndexer;
    private final AcademicDirectory academic;
    private final UserDirectory users;
    private final Clock clock;

    StudentWriter(StudentRepository students, StudentIdentityRepository identities,
                  SocioeconomicProfileRepository profiles, DataConsentRepository consents, BlindIndexer blindIndexer,
                  AcademicDirectory academic, UserDirectory users, Clock clock) {
        this.students = students;
        this.identities = identities;
        this.profiles = profiles;
        this.consents = consents;
        this.blindIndexer = blindIndexer;
        this.academic = academic;
        this.users = users;
        this.clock = clock;
    }

    /** Resultado del alta/actualización. */
    record Written(Student student, UpsertOutcome outcome) {
    }

    Written upsert(StudentRecord r, UUID actor, boolean failIfExists) {
        if (academic.findProgram(r.programId()).isEmpty()) {
            throw DomainException.badRequest("Programa inexistente");
        }
        Instant now = clock.instant();
        StudentIdentity.Data data = identityData(r);
        Optional<StudentIdentity> existing = identities.findByDocumentBlindIndex(data.documentBlindIndex());
        if (existing.isPresent()) {
            if (failIfExists) {
                throw DomainException.conflict("Ya existe un estudiante con ese documento");
            }
            Student student = students.findById(existing.get().getStudentId())
                    .orElseThrow(() -> DomainException.conflict("El documento pertenece a un estudiante eliminado"));
            boolean changed = student.updateAcademic(r.programId(), r.semester(), r.status(), r.admissionDate(), now);
            changed |= existing.get().update(data, now);
            changed |= upsertProfile(student.getId(), r, now);
            linkUserByEmail(student, data.emailBlindIndex());
            return new Written(student, changed ? UpsertOutcome.UPDATED : UpsertOutcome.UNCHANGED);
        }
        Student student = students.save(Student.register(newAnonCode(), r.programId(), r.semester(), r.status(),
                r.admissionDate(), now));
        identities.save(StudentIdentity.of(student.getId(), data, now));
        upsertProfile(student.getId(), r, now);
        if (r.consentVersion() != null && !r.consentVersion().isBlank()) {
            consents.save(DataConsent.record(student.getId(), r.consentVersion().trim(),
                    DataConsent.Purpose.TRATAMIENTO_ACADEMICO, true, DataConsent.Channel.IMPORTACION, actor, now));
        }
        linkUserByEmail(student, data.emailBlindIndex());
        return new Written(student, UpsertOutcome.CREATED);
    }

    StudentIdentity.Data identityData(StudentRecord r) {
        if (r.documentNumber() == null || r.documentNumber().isBlank() || r.fullName() == null || r.fullName().isBlank()) {
            throw DomainException.badRequest("Documento y nombre son obligatorios");
        }
        String document = r.documentNumber().trim();
        String email = blankToNull(r.email()) == null ? null : r.email().trim().toLowerCase(Locale.ROOT);
        return new StudentIdentity.Data(r.fullName().trim(), r.documentType(), document,
                blindIndexer.index(BlindIndexer.Type.DOCUMENT, document), email,
                email == null ? null : blindIndexer.index(BlindIndexer.Type.EMAIL, email), blankToNull(r.phone()));
    }

    String documentIndex(String documentNumber) {
        return blindIndexer.index(BlindIndexer.Type.DOCUMENT, documentNumber.trim());
    }

    /** Si existe una cuenta con el mismo correo, la vincula y le otorga el rol ESTUDIANTE. */
    void linkUserByEmail(Student student, String emailBlindIndex) {
        if (student.getUserId() != null || emailBlindIndex == null) {
            return;
        }
        users.findIdByEmailIndex(emailBlindIndex)
                .filter(userId -> students.findByUserId(userId).isEmpty())
                .ifPresent(userId -> {
                    student.linkUser(userId, clock.instant());
                    users.grantRole(userId, Role.ESTUDIANTE, null);
                });
    }

    private boolean upsertProfile(UUID studentId, StudentRecord r, Instant now) {
        boolean hasData = r.stratum() != null || r.works() != null || r.dependents() != null || r.transport() != null;
        Optional<SocioeconomicProfile> profile = profiles.findById(studentId);
        if (profile.isPresent()) {
            return hasData && profile.get().update(r.stratum(), r.works(), r.dependents(), r.transport(), now);
        }
        if (hasData) {
            profiles.save(SocioeconomicProfile.of(studentId, r.stratum(), r.works(), r.dependents(), r.transport(), now));
            return true;
        }
        return false;
    }

    private String newAnonCode() {
        for (int i = 0; i < ANON_CODE_ATTEMPTS; i++) {
            String code = AnonCodeGenerator.next();
            if (!students.existsByAnonCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("No se pudo generar un anon_code único");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
