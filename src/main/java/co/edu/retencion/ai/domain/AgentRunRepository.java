package co.edu.retencion.ai.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Ejecuciones del agente de retención. */
public interface AgentRunRepository extends JpaRepository<AgentRun, UUID> {
}
