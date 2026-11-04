package co.edu.retencion.records;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import co.edu.retencion.students.infrastructure.SyntheticDataGenerator;
import co.edu.retencion.students.infrastructure.SyntheticDataGenerator.Options;
import co.edu.retencion.support.IntegrationTest;

/**
 * Prueba de volumen de asistencia. Por defecto corre a escala reducida (~200.000 filas) para que
 * {@code ./mvnw verify} sea rápido; la escala completa del criterio de aceptación (5 millones) se
 * ejecuta con:
 *
 * <pre>./mvnw verify -Dit.test=AttendanceVolumeIT -Dretencion.perf.students=30000</pre>
 *
 * Verifica que las consultas de la aplicación (contador por inscripción, asistencia por sesión,
 * historial del estudiante y vencimiento) usen índices y la partición del periodo, y mide su
 * latencia. Resultados en {@code target/perf/attendance-volume.txt} y en {@code docs/rendimiento.md}.
 */
class AttendanceVolumeIT extends IntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AttendanceVolumeIT.class);

    @Autowired
    SyntheticDataGenerator generator;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void attendanceQueriesStayFastAtVolume() throws IOException {
        int students = Integer.getInteger("retencion.perf.students", 1200);
        long started = System.nanoTime();
        String tag = "P" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
        SyntheticDataGenerator.Summary summary = generator.generate(new Options(students, 2, 5, tag));
        UUID termId = jdbc.queryForObject("SELECT id FROM terms WHERE code = ?", UUID.class, summary.termCode());
        Duration generation = Duration.ofNanos(System.nanoTime() - started);

        // Una fila por (inscripción, sesión): ~8 % faltas y ~4 % tardanzas.
        jdbc.queryForObject("SELECT attendance_ensure_partition(?)", String.class, termId);
        started = System.nanoTime();
        int rows = jdbc.update("""
                INSERT INTO attendance_records (id, term_id, session_id, enrollment_id, student_id, group_id, status,
                    justification_status, justification_deadline, recorded_at, updated_at)
                SELECT gen_random_uuid(), g.term_id, cs.id, e.id, e.student_id, e.group_id,
                       CASE WHEN r.x < 0.08 THEN 'FALTA' WHEN r.x < 0.12 THEN 'TARDANZA' ELSE 'PRESENTE' END,
                       'NINGUNA',
                       CASE WHEN r.x < 0.08 THEN cs.session_date + 5 END,
                       now(), now()
                FROM enrollments e
                JOIN course_groups g ON g.id = e.group_id
                JOIN class_sessions cs ON cs.group_id = e.group_id
                CROSS JOIN LATERAL (SELECT random() AS x) r
                WHERE g.term_id = ?
                """, termId);
        Duration load = Duration.ofNanos(System.nanoTime() - started);
        jdbc.execute("ANALYZE attendance_records");
        String partition = "attendance_records_" + termId.toString().replace('-', '_');
        assertThat(rows).isGreaterThan(100_000);

        List<Map<String, Object>> sample = jdbc.queryForList("""
                SELECT e.id AS enrollment_id, e.student_id, cs.id AS session_id
                FROM enrollments e JOIN course_groups g ON g.id = e.group_id
                JOIN class_sessions cs ON cs.group_id = e.group_id
                WHERE g.term_id = ? ORDER BY random() LIMIT 300
                """, termId);

        List<String> report = new ArrayList<>();
        report.add("Filas de asistencia en el periodo: " + rows + " (estudiantes: " + summary.students()
                + ", inscripciones: " + summary.enrollments() + ", sesiones: " + summary.sessions() + ")");
        report.add("Generación de datos sintéticos: " + generation.toSeconds() + " s; carga de asistencia: "
                + load.toSeconds() + " s");

        Query counter = new Query("Contador por inscripción (recalcular)",
                "SELECT status, justification_status FROM attendance_records WHERE term_id = ? AND enrollment_id = ?");
        Query session = new Query("Asistencia de una sesión",
                "SELECT * FROM attendance_records WHERE term_id = ? AND session_id = ?");
        Query history = new Query("Historial del estudiante",
                "SELECT * FROM attendance_records WHERE student_id = ? ORDER BY recorded_at DESC");
        Query overdue = new Query("Faltas vencidas (job diario)",
                "SELECT id FROM attendance_records WHERE status = 'FALTA' AND justification_status = 'NINGUNA'"
                        + " AND justification_deadline < ?");

        report.add(measure(counter, partition, () -> {
            Map<String, Object> row = pick(sample);
            return new Object[] {termId, row.get("enrollment_id")};
        }, 50));
        report.add(measure(session, partition, () -> {
            Map<String, Object> row = pick(sample);
            return new Object[] {termId, row.get("session_id")};
        }, 50));
        report.add(measure(history, partition, () -> new Object[] {pick(sample).get("student_id")}, 50));
        // El job de vencimiento recorre solo el índice parcial (pocas filas: faltas sin justificar).
        report.add(measure(overdue, partition, () -> new Object[] {LocalDate.now().minusYears(5)}, 200));

        Path out = Path.of("target", "perf", "attendance-volume.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, report);
        report.forEach(line -> log.info("[volumen] {}", line));
    }

    private record Query(String name, String sql) {
    }

    /**
     * Ejecuta la consulta 200 veces con parámetros aleatorios y exige p95 bajo el umbral (ms) y un
     * plan sin recorrido secuencial de la partición del periodo.
     */
    private String measure(Query query, String partition, Supplier<Object[]> params, long p95LimitMs) {
        String plan = String.join("\n", jdbc.queryForList("EXPLAIN " + query.sql(), String.class, params.get()));
        assertThat(plan).as("plan de '%s'", query.name()).doesNotContain("Seq Scan on " + partition);
        for (int i = 0; i < 20; i++) {
            jdbc.queryForList(query.sql(), params.get());
        }
        List<Double> times = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            Object[] args = params.get();
            long start = System.nanoTime();
            jdbc.queryForList(query.sql(), args);
            times.add((System.nanoTime() - start) / 1_000_000.0);
        }
        Collections.sort(times);
        double p50 = times.get(99);
        double p95 = times.get(189);
        assertThat(p95).as("p95 de '%s' (ms)", query.name()).isLessThan(p95LimitMs);
        return "%s: p50 %.2f ms, p95 %.2f ms".formatted(query.name(), p50, p95);
    }

    private static Map<String, Object> pick(List<Map<String, Object>> sample) {
        return sample.get(ThreadLocalRandom.current().nextInt(sample.size()));
    }
}
