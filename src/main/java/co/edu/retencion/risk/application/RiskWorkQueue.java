package co.edu.retencion.risk.application;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.common.id.UuidV7;
import co.edu.retencion.records.AttendanceLevel;
import co.edu.retencion.risk.RiskTrigger;

/**
 * Cola de trabajo del motor ({@code risk_work_queue}). Los eventos de notas y asistencia solo
 * encolan (la petición del docente no espera a la IA). ANALYZE tiene antirrebote: una fila por
 * estudiante; un umbral sobre un evento pendiente lo promueve a THRESHOLD y lo adelanta.
 * La reclamación usa {@code FOR UPDATE SKIP LOCKED}: varias instancias no procesan la misma fila.
 */
@Component
public class RiskWorkQueue {

    static final int MAX_ATTEMPTS = 5;

    private final JdbcTemplate jdbc;

    public RiskWorkQueue(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Tarea reclamada. */
    public record Task(UUID id, UUID studentId, String kind, RiskTrigger trigger, UUID enrollmentId, UUID groupId,
                       AttendanceLevel attendanceLevel, int attempts) {
    }

    @Transactional
    public void enqueueAnalysis(UUID studentId, RiskTrigger trigger, AttendanceLevel level, Instant notBefore,
                                Instant now) {
        jdbc.update("""
                INSERT INTO risk_work_queue (id, student_id, kind, trigger_type, attendance_level, not_before, created_at)
                VALUES (?, ?, 'ANALYZE', ?, ?, ?, ?)
                ON CONFLICT (student_id) WHERE kind = 'ANALYZE' DO UPDATE SET
                    trigger_type = CASE WHEN EXCLUDED.trigger_type = 'THRESHOLD' THEN 'THRESHOLD'
                                        ELSE risk_work_queue.trigger_type END,
                    attendance_level = CASE
                        WHEN risk_work_queue.attendance_level IS NULL THEN EXCLUDED.attendance_level
                        WHEN EXCLUDED.attendance_level = 'PERDIDA' THEN 'PERDIDA'
                        WHEN EXCLUDED.attendance_level = 'CRITICO' AND risk_work_queue.attendance_level = 'AVISO' THEN 'CRITICO'
                        ELSE risk_work_queue.attendance_level END,
                    not_before = LEAST(risk_work_queue.not_before, EXCLUDED.not_before)
                """, UuidV7.newId(), studentId, trigger.name(), level == null ? null : level.name(),
                Timestamp.from(notBefore), Timestamp.from(now));
    }

    @Transactional
    public void enqueuePlan(UUID studentId, UUID enrollmentId, UUID groupId, AttendanceLevel level, Instant now) {
        jdbc.update("""
                INSERT INTO risk_work_queue (id, student_id, kind, trigger_type, enrollment_id, group_id,
                                             attendance_level, not_before, created_at)
                VALUES (?, ?, 'SUPPORT_PLAN', 'THRESHOLD', ?, ?, ?, ?, ?)
                ON CONFLICT (enrollment_id, attendance_level) WHERE kind = 'SUPPORT_PLAN' DO NOTHING
                """, UuidV7.newId(), studentId, enrollmentId, groupId, level.name(), Timestamp.from(now),
                Timestamp.from(now));
    }

    /** Reclama (y retira de la cola) hasta {@code limit} tareas vencidas. */
    @Transactional
    public List<Task> claim(Instant now, int limit) {
        return jdbc.query("""
                DELETE FROM risk_work_queue WHERE id IN (
                    SELECT id FROM risk_work_queue WHERE not_before <= ?
                    ORDER BY not_before FOR UPDATE SKIP LOCKED LIMIT ?)
                RETURNING id, student_id, kind, trigger_type, enrollment_id, group_id, attendance_level, attempts
                """, (rs, i) -> new Task(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                RiskTrigger.valueOf(rs.getString(4)), rs.getObject(5, UUID.class), rs.getObject(6, UUID.class),
                rs.getString(7) == null ? null : AttendanceLevel.valueOf(rs.getString(7)), rs.getInt(8)),
                Timestamp.from(now), limit);
    }

    /** Devuelve una tarea fallida a la cola con espera exponencial; tras {@link #MAX_ATTEMPTS} se descarta. */
    @Transactional
    public boolean retry(Task task, String error, Instant now) {
        int attempts = task.attempts() + 1;
        if (attempts >= MAX_ATTEMPTS) {
            return false;
        }
        Instant next = now.plus(Duration.ofMinutes((long) Math.pow(2, attempts)));
        jdbc.update("""
                INSERT INTO risk_work_queue (id, student_id, kind, trigger_type, enrollment_id, group_id,
                                             attendance_level, not_before, attempts, last_error, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, task.id(), task.studentId(), task.kind(), task.trigger().name(), task.enrollmentId(),
                task.groupId(), task.attendanceLevel() == null ? null : task.attendanceLevel().name(),
                Timestamp.from(next), attempts, error == null ? null : error.substring(0, Math.min(200, error.length())),
                Timestamp.from(now));
        return true;
    }

    public int pending() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM risk_work_queue", Integer.class);
        return n == null ? 0 : n;
    }
}
