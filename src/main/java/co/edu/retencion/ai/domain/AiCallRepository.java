package co.edu.retencion.ai.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Bitácora de llamadas a la IA. */
public interface AiCallRepository extends JpaRepository<AiCall, UUID> {

    long countByPurpose(String purpose);
}
