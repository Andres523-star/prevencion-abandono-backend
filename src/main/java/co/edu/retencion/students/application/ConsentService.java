package co.edu.retencion.students.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.students.application.StudentViews.ConsentView;
import co.edu.retencion.students.domain.DataConsent;
import co.edu.retencion.students.domain.DataConsentRepository;
import co.edu.retencion.students.domain.Student;
import co.edu.retencion.students.domain.StudentRepository;

/** Registro e historial de consentimiento de tratamiento de datos. */
@Service
public class ConsentService {

    private final DataConsentRepository consents;
    private final StudentRepository students;
    private final Clock clock;

    public ConsentService(DataConsentRepository consents, StudentRepository students, Clock clock) {
        this.consents = consents;
        this.students = students;
        this.clock = clock;
    }

    @Transactional
    public ConsentView record(UUID studentId, String policyVersion, DataConsent.Purpose purpose, boolean granted,
                              DataConsent.Channel channel) {
        if (!students.existsById(studentId)) {
            throw DomainException.notFound("Estudiante no encontrado");
        }
        return ConsentView.of(consents.save(DataConsent.record(studentId, policyVersion, purpose, granted, channel,
                CurrentUser.require().userId(), clock.instant())));
    }

    /** El estudiante otorga o revoca desde el portal. */
    @Transactional
    public ConsentView recordForCurrentStudent(String policyVersion, DataConsent.Purpose purpose, boolean granted) {
        Student student = students.findByUserId(CurrentUser.require().userId())
                .orElseThrow(() -> DomainException.notFound("Tu cuenta no está vinculada a un estudiante"));
        return record(student.getId(), policyVersion, purpose, granted, DataConsent.Channel.PORTAL);
    }

    @Transactional(readOnly = true)
    public List<ConsentView> history(UUID studentId) {
        return consents.findByStudentIdOrderByRecordedAtDesc(studentId).stream().map(ConsentView::of).toList();
    }

    @Transactional(readOnly = true)
    public List<ConsentView> historyForCurrentStudent() {
        Student student = students.findByUserId(CurrentUser.require().userId())
                .orElseThrow(() -> DomainException.notFound("Tu cuenta no está vinculada a un estudiante"));
        return history(student.getId());
    }
}
