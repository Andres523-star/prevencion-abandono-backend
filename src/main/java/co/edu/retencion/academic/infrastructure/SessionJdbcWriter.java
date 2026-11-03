package co.edu.retencion.academic.infrastructure;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import co.edu.retencion.academic.domain.SessionPlanner.PlannedSession;
import co.edu.retencion.common.id.UuidV7;

/**
 * Escritura masiva de sesiones con JDBC: un periodo puede generar decenas de miles de sesiones,
 * así que se insertan por lotes e idempotentemente ({@code ON CONFLICT DO NOTHING} sobre la clave
 * grupo + fecha + hora de inicio).
 */
@Component
public class SessionJdbcWriter {

    private static final int BATCH = 500;

    private final JdbcTemplate jdbc;

    public SessionJdbcWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return número de sesiones nuevas */
    public int insertMissing(UUID groupId, List<PlannedSession> sessions, Instant now) {
        int created = 0;
        for (int from = 0; from < sessions.size(); from += BATCH) {
            List<PlannedSession> chunk = sessions.subList(from, Math.min(from + BATCH, sessions.size()));
            int[][] results = jdbc.batchUpdate("""
                    INSERT INTO class_sessions (id, group_id, schedule_id, session_date, start_time, end_time, room,
                                                status, created_at, updated_at, version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'PROGRAMADA', ?, ?, 0)
                    ON CONFLICT (group_id, session_date, start_time) DO NOTHING
                    """, chunk, chunk.size(), (ps, s) -> {
                ps.setObject(1, UuidV7.newId());
                ps.setObject(2, groupId);
                ps.setObject(3, s.scheduleId());
                ps.setDate(4, Date.valueOf(s.date()));
                ps.setTime(5, Time.valueOf(s.start()));
                ps.setTime(6, Time.valueOf(s.end()));
                ps.setString(7, s.room());
                ps.setTimestamp(8, Timestamp.from(now));
                ps.setTimestamp(9, Timestamp.from(now));
            });
            created += Arrays.stream(results).flatMapToInt(Arrays::stream).map(r -> Math.max(r, 0)).sum();
        }
        return created;
    }

    /** Borra las sesiones futuras aún programadas de un grupo (al cambiar su horario). */
    public int deleteFutureScheduled(UUID groupId, LocalDate fromDate) {
        return jdbc.update("DELETE FROM class_sessions WHERE group_id = ? AND status = 'PROGRAMADA' AND session_date >= ?",
                groupId, Date.valueOf(fromDate));
    }

    /**
     * Cancela las sesiones programadas en un rango (festivo o suspensión).
     *
     * @param programId {@code null} = todos los programas
     * @return sesiones canceladas
     */
    public int cancelScheduled(LocalDate from, LocalDate to, UUID programId, String reason, Instant now) {
        return jdbc.update("""
                UPDATE class_sessions s
                SET status = 'CANCELADA', cancellation_reason = ?, updated_at = ?, version = s.version + 1
                FROM course_groups g JOIN subjects sub ON sub.id = g.subject_id
                WHERE s.group_id = g.id AND s.status = 'PROGRAMADA'
                  AND s.session_date BETWEEN ? AND ?
                  AND (CAST(? AS uuid) IS NULL OR sub.program_id = CAST(? AS uuid))
                """, reason, Timestamp.from(now), Date.valueOf(from), Date.valueOf(to), programId, programId);
    }
}
