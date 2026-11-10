package co.edu.retencion.records;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.common.time.TimeConfig;
import co.edu.retencion.students.infrastructure.SyntheticDataGenerator;
import co.edu.retencion.students.infrastructure.SyntheticDataGenerator.Options;
import co.edu.retencion.support.AttendanceWorld.Actor;
import co.edu.retencion.support.RecordsIntegrationTest;

/**
 * Pico de asistencia: todos los docentes toman lista a la misma hora. Cada sesión del día con más clases se
 * registra a la vez por HTTP real (un docente por grupo), y luego se verifica que no se perdió nada: una
 * fila por inscripción en la partición del periodo y contadores de faltas exactos.
 *
 * <p>En {@code ./mvnw verify} corre a escala reducida con un cliente HTTP concurrente. La escala completa, con
 * Gatling:
 *
 * <pre>./mvnw verify -Dit.test=AttendancePeakIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false
 *     -Dretencion.perf.students=50000 -Dretencion.perf.gatling=true</pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AttendancePeakIT extends RecordsIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AttendancePeakIT.class);

    @Autowired
    SyntheticDataGenerator generator;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MeterRegistry meters;
    @LocalServerPort
    int port;

    private record Task(String token, UUID sessionId, UUID groupId, String body, int faltas, int tardanzas, int entries) {
    }

    @Test
    void everyoneTakesAttendanceAtTheSameTime() throws Exception {
        int students = Integer.getInteger("retencion.perf.students", 1200);
        String tag = "K" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
        SyntheticDataGenerator.Summary summary = generator.generate(new Options(students, 2, 5, tag));
        UUID term = jdbc.queryForObject("SELECT id FROM terms WHERE code = ?", UUID.class, summary.termCode());

        // La franja más concurrida del periodo: el día y la hora con más clases que terminan a la vez.
        Map<String, Object> slot = jdbc.queryForMap("""
                SELECT cs.session_date, cs.end_time FROM class_sessions cs JOIN course_groups g ON g.id = cs.group_id
                WHERE g.term_id = ? AND cs.status = 'PROGRAMADA'
                GROUP BY cs.session_date, cs.end_time ORDER BY count(*) DESC, cs.session_date LIMIT 1
                """, term);
        LocalDate day = ((java.sql.Date) slot.get("session_date")).toLocalDate();
        List<Map<String, Object>> sessions = jdbc.queryForList("""
                SELECT cs.id, cs.group_id, cs.end_time FROM class_sessions cs JOIN course_groups g ON g.id = cs.group_id
                WHERE g.term_id = ? AND cs.session_date = ? AND cs.end_time = ? AND cs.status = 'PROGRAMADA'
                """, term, day, slot.get("end_time"));
        LocalTime lastEnd = sessions.stream().map(s -> ((java.sql.Time) s.get("end_time")).toLocalTime())
                .max(LocalTime::compareTo).orElseThrow();
        // Todos toman lista justo después de la última clase del día, dentro de la ventana de edición.
        clock.setNow(ZonedDateTime.of(day, lastEnd.plusMinutes(5), TimeConfig.BUSINESS_ZONE).toInstant());

        List<UUID> groups = sessions.stream().map(s -> (UUID) s.get("group_id")).distinct().toList();
        int teacherCount = Math.min(groups.size(), Integer.getInteger("retencion.perf.teachers", 40));
        List<Actor> teachers = new ArrayList<>();
        for (int i = 0; i < teacherCount; i++) {
            teachers.add(staff(Set.of(Role.DOCENTE), Set.of()));
        }
        Map<UUID, Actor> teacherOf = new HashMap<>();
        for (int i = 0; i < groups.size(); i++) {
            Actor teacher = teachers.get(i % teacherCount);
            teacherOf.put(groups.get(i), teacher);
            jdbc.update("UPDATE course_groups SET teacher_user_id = ? WHERE id = ?", teacher.userId(), groups.get(i));
        }

        List<Task> tasks = new ArrayList<>();
        for (Map<String, Object> s : sessions) {
            UUID group = (UUID) s.get("group_id");
            List<UUID> enrollments = jdbc.queryForList("SELECT id FROM enrollments WHERE group_id = ? AND status = 'ACTIVA'",
                    UUID.class, group);
            if (enrollments.isEmpty()) {
                continue; // grupo sin inscritos: no hay lista que tomar
            }
            List<Map<String, Object>> entries = new ArrayList<>();
            int faltas = 0;
            int tardanzas = 0;
            for (UUID e : enrollments) {
                int bucket = Math.floorMod(e.hashCode(), 100);
                String status = bucket < 8 ? "FALTA" : bucket < 11 ? "TARDANZA" : "PRESENTE";
                faltas += "FALTA".equals(status) ? 1 : 0;
                tardanzas += "TARDANZA".equals(status) ? 1 : 0;
                entries.add(Map.of("enrollmentId", e, "status", status));
            }
            tasks.add(new Task(teacherOf.get(group).tokens().accessToken(), (UUID) s.get("id"), group,
                    json.writeValueAsString(Map.of("entries", entries)), faltas, tardanzas, entries.size()));
        }
        int expectedRows = tasks.stream().mapToInt(Task::entries).sum();
        List<String> report = new ArrayList<>();
        report.add("Estudiantes: " + summary.students() + "; franja " + day + " " + lastEnd + ": " + tasks.size() + " sesiones, "
                + groups.size() + " grupos, " + teacherCount + " docentes, " + expectedRows + " registros");

        if (Boolean.getBoolean("retencion.perf.gatling")) {
            report.add(gatling(tasks));
        } else {
            report.add(concurrent(tasks));
        }

        Long rows = jdbc.queryForObject("SELECT count(*) FROM attendance_records WHERE term_id = ? AND session_id = ANY (?)",
                Long.class, term, sessionArray(tasks));
        assertThat(rows).as("una fila por inscripción y sesión, sin pérdidas ni duplicados").isEqualTo(expectedRows);
        String partition = "attendance_records_" + term.toString().replace('-', '_');
        Long inPartition = jdbc.queryForObject("SELECT count(*) FROM ONLY " + partition + " WHERE session_id = ANY (?)",
                Long.class, (Object) sessionArray(tasks));
        assertThat(inPartition).as("todo cae en la partición del periodo").isEqualTo(expectedRows);
        String plan = String.join("\n", jdbc.queryForList("EXPLAIN SELECT * FROM attendance_records WHERE term_id = ? AND session_id = ?",
                String.class, term, tasks.get(0).sessionId()));
        assertThat(plan).as("poda de particiones").contains(partition).doesNotContain("attendance_records_default");
        Long faltas = jdbc.queryForObject("SELECT COALESCE(sum(unjustified), 0) FROM absence_counters WHERE term_id = ?",
                Long.class, term);
        Long tardies = jdbc.queryForObject("SELECT COALESCE(sum(tardies), 0) FROM absence_counters WHERE term_id = ?",
                Long.class, term);
        assertThat(faltas).as("contadores de faltas exactos bajo concurrencia").isEqualTo(tasks.stream().mapToInt(Task::faltas).sum());
        assertThat(tardies).isEqualTo(tasks.stream().mapToInt(Task::tardanzas).sum());
        report.add("Verificado: " + rows + " registros en " + partition + "; faltas " + faltas + ", tardanzas " + tardies);

        // El trabajo diferido (correo, cola de riesgo, analítica) termina después de las respuestas.
        long drainStart = System.nanoTime();
        long pending = Long.MAX_VALUE;
        while (pending > 0 && System.nanoTime() - drainStart < TimeUnit.MINUTES.toNanos(10)) {
            pending = jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE completion_date IS NULL", Long.class);
            if (pending > 0) {
                Thread.sleep(250);
            }
        }
        report.add(String.format("Eventos diferidos completados %.1f s después de la última respuesta (pendientes: %d)",
                (System.nanoTime() - drainStart) / 1_000_000_000.0, pending));

        Path out = Path.of("target", "perf", "attendance-peak.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, report);
        report.forEach(line -> log.info("[pico] {}", line));
    }

    private static UUID[] sessionArray(List<Task> tasks) {
        return tasks.stream().map(Task::sessionId).toArray(UUID[]::new);
    }

    /** Todas las tomas de lista a la vez, con hasta N en vuelo, midiendo latencia y espera por conexiones. */
    private String concurrent(List<Task> tasks) throws Exception {
        int parallel = Integer.getInteger("retencion.perf.parallel", 64);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        Semaphore permits = new Semaphore(parallel);
        ConcurrentLinkedQueue<Double> times = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<Double> maxPending = new AtomicReference<>(0.0);
        AtomicReference<Double> maxActive = new AtomicReference<>(0.0);
        Map<String, Integer> waits = new java.util.concurrent.ConcurrentHashMap<>();
        Thread sampler = Thread.ofVirtual().start(() -> {
            try (java.sql.Connection c = co.edu.retencion.support.TestDatabase.get().ownerConnection();
                 java.sql.PreparedStatement ps = c.prepareStatement("SELECT coalesce(wait_event_type, 'CPU') || ':' "
                         + "|| coalesce(wait_event, '-') || ' ' || left(regexp_replace(query, '\\s+', ' ', 'g'), 70) "
                         + "FROM pg_stat_activity WHERE state = 'active' AND pid <> pg_backend_pid() AND backend_type = 'client backend'")) {
                while (running.get()) {
                    sample("hikaricp.connections.pending", maxPending);
                    sample("hikaricp.connections.active", maxActive);
                    try (java.sql.ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            waits.merge(rs.getString(1), 1, Integer::sum);
                        }
                    }
                    Thread.sleep(50);
                }
            } catch (Exception ex) {
                log.warn("Muestreo de esperas interrumpido: {}", ex.getClass().getSimpleName());
            }
        });
        Thread unused = Thread.ofVirtual().start(() -> {
            while (running.get()) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex) {
                    return;
                }
            }
        });
        long started = System.nanoTime();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (Task t : tasks) {
                futures.add(pool.submit(() -> {
                    permits.acquireUninterruptibly();
                    try {
                        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/sessions/"
                                        + t.sessionId() + "/attendance"))
                                .header("Authorization", "Bearer " + t.token()).header("Content-Type", "application/json")
                                .timeout(Duration.ofSeconds(60)).PUT(HttpRequest.BodyPublishers.ofString(t.body())).build();
                        long start = System.nanoTime();
                        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                        times.add((System.nanoTime() - start) / 1_000_000.0);
                        if (response.statusCode() != 200) {
                            errors.add(response.statusCode() + " " + response.body());
                        }
                    } catch (Exception ex) {
                        errors.add(ex.getClass().getSimpleName());
                    } finally {
                        permits.release();
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.MINUTES);
            }
        } finally {
            running.set(false);
            sampler.join(2000);
            unused.join(1000);
        }
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        String topWaits = waits.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(6)
                .map(e -> e.getValue() + "× " + e.getKey()).collect(java.util.stream.Collectors.joining(" | "));
        log.info("[pico] esperas en PostgreSQL (muestras): {}", topWaits);
        List<Double> sorted = new ArrayList<>(times);
        Collections.sort(sorted);
        double p95 = sorted.get((int) Math.ceil(0.95 * sorted.size()) - 1);
        log.info("[pico] p50 {} ms, p95 {} ms, errores {}", sorted.get(sorted.size() / 2), p95, errors.size());
        assertThat(errors).as("todas las tomas de lista responden 200").isEmpty();
        // Tope amplio: aquí importa que nada falle ni se pierda; la latencia se documenta (docs/rendimiento.md).
        double limit = Integer.getInteger("retencion.perf.peak-p95-ms", 30000);
        assertThat(p95).as("p95 de la toma de lista en el pico").isLessThan(limit);
        return String.format("Cliente concurrente (%d en vuelo): %d peticiones en %.1f s; p50 %.0f ms, p95 %.0f ms, máx %.0f ms; "
                        + "conexiones activas máx %.0f, en espera máx %.0f", parallel, sorted.size(), seconds,
                sorted.get(sorted.size() / 2), p95, sorted.get(sorted.size() - 1), maxActive.get(), maxPending.get());
    }

    private void sample(String name, AtomicReference<Double> max) {
        Gauge gauge = meters.find(name).gauge();
        if (gauge != null) {
            max.accumulateAndGet(gauge.value(), Math::max);
        }
    }

    /** Lanza {@code perf/AttendancePeakSimulation} en otra JVM: todas las peticiones a la vez. */
    private String gatling(List<Task> tasks) throws Exception {
        Path feeder = Path.of("target", "perf", "attendance-peak.json").toAbsolutePath();
        Files.createDirectories(feeder.getParent());
        List<Map<String, String>> rows = new ArrayList<>();
        for (Task t : tasks) {
            rows.add(Map.of("token", t.token(), "path", "/api/v1/sessions/" + t.sessionId() + "/attendance", "body", t.body()));
        }
        json.writeValue(feeder.toFile(), rows);
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> command = new ArrayList<>(windows ? List.of("cmd", "/c", ".\\mvnw.cmd") : List.of("./mvnw"));
        command.addAll(List.of("-f", "perf/pom.xml", "gatling:test", "-Dsimulation=retencion.AttendancePeakSimulation",
                "-DbaseUrl=http://localhost:" + port, "-Dfeeder=target/perf/attendance-peak.json", "-Dusers=" + tasks.size(),
                "-DrampSeconds=" + Integer.getInteger("retencion.perf.ramp-seconds", 60),
                "-Dp95Ms=" + Integer.getInteger("retencion.perf.peak-p95-ms", 30000)));
        File output = Path.of("target", "perf", "gatling-attendance.log").toFile();
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output).start();
        assertThat(process.waitFor(30, TimeUnit.MINUTES)).as("Gatling terminó").isTrue();
        assertThat(process.exitValue()).as("Gatling cumple las aserciones; ver target/perf/gatling-attendance.log").isZero();
        return "Gatling: " + tasks.size() + " tomas de lista; ver target/perf/gatling-attendance.log";
    }
}
