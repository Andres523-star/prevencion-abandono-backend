package co.edu.retencion.ai.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Llamadas a herramientas del agente (append-only). */
public interface AgentToolCallRepository extends JpaRepository<AgentToolCall, UUID> {

    List<AgentToolCall> findByRunIdOrderBySeqAsc(UUID runId);
}
