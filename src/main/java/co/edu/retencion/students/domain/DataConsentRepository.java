package co.edu.retencion.students.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Repositorio del historial de consentimientos. */
public interface DataConsentRepository extends JpaRepository<DataConsent, UUID> {

    List<DataConsent> findByStudentIdOrderByRecordedAtDesc(UUID studentId);
}
