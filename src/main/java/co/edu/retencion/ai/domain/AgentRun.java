package co.edu.retencion.ai.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import co.edu.retencion.common.id.UuidV7;

/**
 * Ejecución del agente de retención. Su único producto es un BORRADOR para revisión humana: el
 * agente no envía mensajes ni ejecuta acciones.
 */
@Entity
@Table(name = "agent_runs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentRun {

    /** Estado de la ejecución. */
    public enum Status {
        EN_CURSO,
        BORRADOR,
        FALLIDO
    }

    @Id
    private UUID id;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private String model;

    @Column(name = "prompt_version", nullable = false)
    private String promptVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String draft;

    @Column(name = "tool_calls", nullable = false)
    private int toolCalls;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    public static AgentRun start(UUID studentId, String model, String promptVersion, UUID requestedBy, Instant now) {
        AgentRun r = new AgentRun();
        r.id = UuidV7.newId();
        r.studentId = studentId;
        r.status = Status.EN_CURSO;
        r.model = model;
        r.promptVersion = promptVersion;
        r.requestedBy = requestedBy;
        r.createdAt = now;
        return r;
    }

    public void toolCalled() {
        this.toolCalls++;
    }

    public void addTokens(long input, long output) {
        this.inputTokens = (int) Math.min(Integer.MAX_VALUE, (long) inputTokens + input);
        this.outputTokens = (int) Math.min(Integer.MAX_VALUE, (long) outputTokens + output);
    }

    public void completeWithDraft(String draftJson, String respondingModel, Instant now) {
        this.status = Status.BORRADOR;
        this.draft = draftJson;
        if (respondingModel != null) {
            this.model = respondingModel;
        }
        this.finishedAt = now;
    }

    public void fail(String reason, Instant now) {
        this.status = Status.FALLIDO;
        this.failureReason = reason;
        this.finishedAt = now;
    }
}
