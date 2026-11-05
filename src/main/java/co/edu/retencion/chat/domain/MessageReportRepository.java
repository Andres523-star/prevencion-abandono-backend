package co.edu.retencion.chat.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Reportes de mensajes. */
public interface MessageReportRepository extends JpaRepository<MessageReport, UUID> {

    List<MessageReport> findByStatusOrderByCreatedAtDesc(String status);
}
