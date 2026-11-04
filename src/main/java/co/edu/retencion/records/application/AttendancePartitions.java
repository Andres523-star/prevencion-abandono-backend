package co.edu.retencion.records.application;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Asegura que exista la partición de asistencia del periodo antes de la primera escritura (función
 * SQL {@code attendance_ensure_partition}, idempotente). Se recuerda por proceso para no llamarla
 * en cada petición.
 */
@Component
class AttendancePartitions {

    private final JdbcTemplate jdbc;
    private final Set<UUID> known = ConcurrentHashMap.newKeySet();

    AttendancePartitions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void ensure(UUID termId) {
        if (!known.contains(termId)) {
            jdbc.queryForObject("SELECT attendance_ensure_partition(?)", String.class, termId);
            known.add(termId);
        }
    }
}
