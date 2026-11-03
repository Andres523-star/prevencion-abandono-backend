package co.edu.retencion.audit.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import co.edu.retencion.audit.AuditOutcome;

/**
 * Entrada inmutable de la bitácora de auditoría.
 *
 * <p><b>Patrón creacional: Builder.</b> Una entrada tiene muchos campos opcionales (actor, IP,
 * motivo, entidad...). El {@link Builder} evita constructores con una docena de parámetros
 * posicionales y valida los obligatorios al construir. Los campos de la cadena (seq, prevHash,
 * hash) solo los fija el sink al persistir, mediante {@link #chained(long, String, String)}.
 */
public final class AuditEntry {

    private final UUID id;
    private final Instant occurredAt;
    private final UUID actorUserId;
    private final String actorRoles;
    private final String action;
    private final String entityType;
    private final String entityId;
    private final AuditOutcome outcome;
    private final String ipAddress;
    private final String requestId;
    private final String reason;
    private final Map<String, String> details;
    private final long seq;
    private final String prevHash;
    private final String hash;

    private AuditEntry(Builder b, long seq, String prevHash, String hash) {
        this.id = Objects.requireNonNull(b.id, "id");
        // PostgreSQL guarda microsegundos: se trunca aquí para que el hash recalculado coincida.
        this.occurredAt = Objects.requireNonNull(b.occurredAt, "occurredAt").truncatedTo(ChronoUnit.MICROS);
        this.actorUserId = b.actorUserId;
        this.actorRoles = b.actorRoles;
        this.action = requireText(b.action, "action");
        this.entityType = b.entityType;
        this.entityId = b.entityId;
        this.outcome = Objects.requireNonNull(b.outcome, "outcome");
        this.ipAddress = b.ipAddress;
        this.requestId = b.requestId;
        this.reason = b.reason;
        this.details = Map.copyOf(new TreeMap<>(b.details));
        this.seq = seq;
        this.prevHash = prevHash;
        this.hash = hash;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Copia editable (la usan los decoradores, p. ej. para depurar detalles). */
    public Builder toBuilder() {
        return new Builder()
                .id(id).occurredAt(occurredAt).actor(actorUserId, actorRoles).action(action)
                .entity(entityType, entityId).outcome(outcome).ipAddress(ipAddress)
                .requestId(requestId).reason(reason).details(details);
    }

    /** Devuelve la misma entrada con la posición y los hashes de la cadena. */
    public AuditEntry chained(long seq, String prevHash, String hash) {
        return new AuditEntry(toBuilder(), seq, prevHash, hash);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value;
    }

    public UUID id() { return id; }
    public Instant occurredAt() { return occurredAt; }
    public UUID actorUserId() { return actorUserId; }
    public String actorRoles() { return actorRoles; }
    public String action() { return action; }
    public String entityType() { return entityType; }
    public String entityId() { return entityId; }
    public AuditOutcome outcome() { return outcome; }
    public String ipAddress() { return ipAddress; }
    public String requestId() { return requestId; }
    public String reason() { return reason; }
    public Map<String, String> details() { return details; }
    public long seq() { return seq; }
    public String prevHash() { return prevHash; }
    public String hash() { return hash; }

    /** Constructor paso a paso de {@link AuditEntry}. */
    public static final class Builder {

        private UUID id;
        private Instant occurredAt;
        private UUID actorUserId;
        private String actorRoles;
        private String action;
        private String entityType;
        private String entityId;
        private AuditOutcome outcome;
        private String ipAddress;
        private String requestId;
        private String reason;
        private Map<String, String> details = Map.of();

        private Builder() {
        }

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder occurredAt(Instant occurredAt) {
            this.occurredAt = occurredAt;
            return this;
        }

        public Builder actor(UUID userId, String roles) {
            this.actorUserId = userId;
            this.actorRoles = roles;
            return this;
        }

        public Builder action(String action) {
            this.action = action;
            return this;
        }

        public Builder entity(String type, String id) {
            this.entityType = blankToNull(type);
            this.entityId = blankToNull(truncate(id, 100));
            return this;
        }

        public Builder outcome(AuditOutcome outcome) {
            this.outcome = outcome;
            return this;
        }

        public Builder ipAddress(String ipAddress) {
            this.ipAddress = truncate(ipAddress, 45);
            return this;
        }

        public Builder requestId(String requestId) {
            this.requestId = truncate(requestId, 64);
            return this;
        }

        public Builder reason(String reason) {
            this.reason = blankToNull(truncate(reason, 500));
            return this;
        }

        public Builder details(Map<String, String> details) {
            this.details = details == null ? Map.of() : details;
            return this;
        }

        public AuditEntry build() {
            return new AuditEntry(this, 0L, null, null);
        }

        private static String truncate(String value, int max) {
            return value == null || value.length() <= max ? value : value.substring(0, max);
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }
}
