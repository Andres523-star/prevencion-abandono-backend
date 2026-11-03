package co.edu.retencion.audit.infrastructure;

import java.sql.Timestamp;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;

import co.edu.retencion.audit.domain.AuditEntry;
import co.edu.retencion.audit.domain.AuditEventSink;
import co.edu.retencion.audit.domain.HashChain;

/**
 * Implementación base (componente concreto del Decorator): escribe en {@code audit_log}.
 *
 * <p>Para que la cadena sea consistente con varias instancias concurrentes, se bloquea la fila
 * única de {@code audit_chain_head} ({@code SELECT ... FOR UPDATE}) mientras se calcula el hash
 * y se inserta. La secuencia es continua (sin huecos), así un borrado se detecta como salto.
 * Debe ejecutarse dentro de una transacción (la abre {@code AuditService}).
 */
public class JdbcAuditEventSink implements AuditEventSink {

    private static final String INSERT = """
            INSERT INTO audit_log (id, seq, occurred_at, actor_user_id, actor_roles, action, entity_type,
                                   entity_id, outcome, ip_address, request_id, reason, details, prev_hash, hash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAuditEventSink(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public AuditEntry append(AuditEntry entry) {
        Map<String, Object> head = jdbc.queryForMap(
                "SELECT last_seq, last_hash FROM audit_chain_head WHERE id = 1 FOR UPDATE");
        long seq = ((Number) head.get("last_seq")).longValue() + 1;
        String prevHash = ((String) head.get("last_hash")).trim();
        AuditEntry chained = entry.chained(seq, prevHash, HashChain.compute(prevHash, seq, entry));

        jdbc.update(INSERT,
                chained.id(), seq, Timestamp.from(chained.occurredAt()), chained.actorUserId(), chained.actorRoles(),
                chained.action(), chained.entityType(), chained.entityId(), chained.outcome().name(),
                chained.ipAddress(), chained.requestId(), chained.reason(), toJson(chained.details()),
                chained.prevHash(), chained.hash());
        jdbc.update("UPDATE audit_chain_head SET last_seq = ?, last_hash = ? WHERE id = 1", seq, chained.hash());
        return chained;
    }

    private String toJson(Map<String, String> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Detalles de auditoría no serializables", ex);
        }
    }
}
