package co.edu.retencion.ai.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import co.edu.retencion.common.id.UuidV7;

/**
 * Registro (append-only) de cada llamada del agente a una herramienta de solo lectura: nombre,
 * argumentos y hash de la salida (la salida misma no se guarda: ya está en las tablas de origen).
 */
@Entity
@Table(name = "agent_tool_calls")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentToolCall {

    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(nullable = false)
    private int seq;

    @Column(name = "tool_name", nullable = false)
    private String toolName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_input", nullable = false, columnDefinition = "jsonb")
    private String toolInput;

    @Column(name = "output_hash", nullable = false)
    private String outputHash;

    @Column(name = "output_size", nullable = false)
    private int outputSize;

    @Column(name = "is_error", nullable = false)
    private boolean error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static AgentToolCall of(UUID runId, int seq, String toolName, String inputJson, String outputHash,
                                   int outputSize, boolean error, Instant now) {
        AgentToolCall c = new AgentToolCall();
        c.id = UuidV7.newId();
        c.runId = runId;
        c.seq = seq;
        c.toolName = toolName;
        c.toolInput = inputJson;
        c.outputHash = outputHash;
        c.outputSize = outputSize;
        c.error = error;
        c.createdAt = now;
        return c;
    }
}
