package co.edu.retencion.audit.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import co.edu.retencion.audit.AuditOutcome;
import co.edu.retencion.audit.domain.AuditEntry;

/**
 * Lectura de la bitácora por bloques ordenados por {@code seq} (keyset), para verificar la cadena
 * sin cargar millones de filas en memoria.
 */
@Component
public class AuditLogReader {

    private static final TypeReference<Map<String, String>> DETAILS = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AuditLogReader(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<AuditEntry> readAfter(long afterSeq, int limit) {
        return jdbc.query("""
                SELECT id, seq, occurred_at, actor_user_id, actor_roles, action, entity_type, entity_id, outcome,
                       ip_address, request_id, reason, details::text AS details, prev_hash, hash
                FROM audit_log WHERE seq > ? ORDER BY seq LIMIT ?
                """, (rs, i) -> map(rs), afterSeq, limit);
    }

    private AuditEntry map(ResultSet rs) throws SQLException {
        Map<String, String> details;
        try {
            details = objectMapper.readValue(rs.getString("details"), DETAILS);
        } catch (Exception ex) {
            throw new SQLException("Detalles de auditoría ilegibles", ex);
        }
        return AuditEntry.builder()
                .id(rs.getObject("id", UUID.class))
                .occurredAt(rs.getTimestamp("occurred_at").toInstant())
                .actor(rs.getObject("actor_user_id", UUID.class), rs.getString("actor_roles"))
                .action(rs.getString("action"))
                .entity(rs.getString("entity_type"), rs.getString("entity_id"))
                .outcome(AuditOutcome.valueOf(rs.getString("outcome")))
                .ipAddress(rs.getString("ip_address"))
                .requestId(rs.getString("request_id"))
                .reason(rs.getString("reason"))
                .details(details)
                .build()
                .chained(rs.getLong("seq"), rs.getString("prev_hash").trim(), rs.getString("hash").trim());
    }
}
