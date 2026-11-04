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

import co.edu.retencion.common.id.UuidV7;

/**
 * Bitácora de una llamada lógica a la IA (con sus reintentos). Sin contenido: solo modelo,
 * versión del prompt, hash del payload, resultado y consumo. Sirve para costos y auditoría técnica.
 */
@Entity
@Table(name = "ai_calls")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiCall {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String purpose;

    @Column(nullable = false)
    private String model;

    @Column(name = "prompt_version", nullable = false)
    private String promptVersion;

    @Column(name = "payload_hash", nullable = false)
    private String payloadHash;

    @Column(nullable = false)
    private String outcome;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static AiCall of(String purpose, String model, String promptVersion, String payloadHash, String outcome,
                            int attempts, long inputTokens, long outputTokens, long latencyMs, Instant now) {
        AiCall c = new AiCall();
        c.id = UuidV7.newId();
        c.purpose = purpose;
        c.model = model;
        c.promptVersion = promptVersion;
        c.payloadHash = payloadHash;
        c.outcome = outcome;
        c.attempts = attempts;
        c.inputTokens = (int) Math.min(Integer.MAX_VALUE, inputTokens);
        c.outputTokens = (int) Math.min(Integer.MAX_VALUE, outputTokens);
        c.latencyMs = (int) Math.min(Integer.MAX_VALUE, latencyMs);
        c.createdAt = now;
        return c;
    }
}
