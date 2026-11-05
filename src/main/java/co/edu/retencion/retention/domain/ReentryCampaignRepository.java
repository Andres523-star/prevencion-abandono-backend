package co.edu.retencion.retention.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Repositorio del módulo de retención. */
public interface ReentryCampaignRepository extends JpaRepository<ReentryCampaign, UUID> {

    List<ReentryCampaign> findAllByOrderByCreatedAtDesc();
}
