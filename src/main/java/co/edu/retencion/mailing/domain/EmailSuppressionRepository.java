package co.edu.retencion.mailing.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailSuppressionRepository extends JpaRepository<EmailSuppression, String> {
}
