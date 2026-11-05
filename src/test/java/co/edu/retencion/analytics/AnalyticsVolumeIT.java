package co.edu.retencion.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import co.edu.retencion.analytics.AnalyticsQueries.Granularity;
import co.edu.retencion.analytics.infrastructure.AnalyticsRepository;
import co.edu.retencion.students.infrastructure.SyntheticDataGenerator;
import co.edu.retencion.support.AnalyticsDataset;
import co.edu.retencion.support.AnalyticsDataset.Dataset;
import co.edu.retencion.support.AttendanceWorld.Actor;
import co.edu.retencion.support.RecordsIntegrationTest;

/**
 * Rendimiento de la analítica. En {@code ./mvnw verify} corre a escala reducida; la escala del criterio
 * de aceptación (50.000 estudiantes y más de 5 millones de filas de asistencia), con carga de Gatling:
 *
 * <pre>./mvnw verify -Dit.test=AnalyticsVolumeIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false
 *     -Dretencion.perf.students=50000 -Dretencion.perf.attendance=true -Dretencion.perf.gatling=true</pre>
 *
 * Mide las consultas <b>sin caché</b> (el peor caso, justo después de un refresco), guarda sus planes y
 * exige p95 &lt; 500 ms. Resultados en {@code target/perf/} y {@code docs/rendimiento.md}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnalyticsVolumeIT extends RecordsIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsVolumeIT.class);
    private static final double LIMIT_MS = 500;

    @Autowired
    SyntheticDataGenerator generator;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AnalyticsRepository analytics;
    @LocalServerPort
    int port;

    @Test
    void queriesStayUnderTheLimitAtVolume() throws Exception {
        int students = Integer.getInteger("retencion.perf.students", 1200);
        long started = System.nanoTime();
        Dataset d = AnalyticsDataset.create(generator, jdbc, students);
        List<String> report = new ArrayList<>();
        report.add("Estudiantes: " + d.students() + "; datos: " + seconds(started) + " s");
        if (Boolean.getBoolean("retencion.perf.attendance")) {
            report.add(loadAttendance(d.termId()));
        }
        report.add("Inscripciones: " + jdbc.queryForObject("SELECT count(*) FROM enrollments WHERE term_id = ?", Long.class, d.termId()));

        started = System.nanoTime();
        Actor admin = staff(java.util.Set.of(co.edu.retencion.common.security.Role.ADMIN), java.util.Set.of());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/analytics/refresh")
                .header("Authorization", bearer(admin.tokens()))).andExpect(status().isOk());
        report.add("Refresco de las vistas: " + seconds(started) + " s");
        jdbc.execute("ANALYZE analytics_student_facts");
        jdbc.execute("ANALYZE analytics_attendance_cube");
        report.add("Filas de analytics_student_facts: " + jdbc.queryForObject("SELECT count(*) FROM analytics_student_facts", Long.class));
        report.add("Filas de analytics_attendance_cube: " + jdbc.queryForObject("SELECT count(*) FROM analytics_attendance_cube", Long.class));

        AnalyticsViewer all = AnalyticsViewer.all();
        report.add(measure("Deserción por programa", () -> analytics.dropoutByProgram(filter(d), all)));
        report.add(measure("Tendencia mensual", () -> analytics.liveTrend(filter(d), Granularity.MONTH, all)));
        report.add(measure("Tendencia anual", () -> analytics.liveTrend(filter(d), Granularity.YEAR, all)));
        report.add(measure("Por semestre", () -> analytics.bySemester(filter(d), all)));
        report.add(measure("Distribución de riesgo", () -> analytics.riskDistribution(filter(d), all)));
        report.add(measure("Materias con más faltas", () -> analytics.subjects(filter(d), 20, all)));
        report.add(measure("Embudo", () -> analytics.funnel(filter(d), all)));
        report.add(measure("Totales del tablero", () -> analytics.overview(all)));
        report.add(measure("Sin filtro de periodo", () -> analytics.dropoutByProgram(AnalyticsFilter.none(), all)));

        report.add(explain("Por programa y periodo", """
                SELECT program_id, count(DISTINCT student_id) FROM analytics_student_facts
                WHERE term_id = ? AND program_id = ? GROUP BY program_id
                """, d.termId(), ISW));
        report.add(explain("Materias del periodo", """
                SELECT subject_id, sum(absences) FROM analytics_attendance_cube WHERE term_id = ? GROUP BY subject_id
                """, d.termId()));

        // Peticiones reales con caché: la experiencia normal del usuario.
        List<Double> http = new ArrayList<>();
        String[] paths = {"dropout-by-program", "by-semester", "risk-distribution", "attendance-risk", "retention-funnel", "trend"};
        for (int i = 0; i < 300; i++) {
            String path = paths[i % paths.length];
            long start = System.nanoTime();
            mvc.perform(get("/api/v1/analytics/" + path).param("termId", d.termId().toString())
                    .header("Authorization", bearer(admin.tokens()))).andExpect(status().isOk());
            http.add((System.nanoTime() - start) / 1_000_000.0);
        }
        report.add(String.format("Endpoints con caché (MockMvc): p95 %.1f ms", percentile(http, 0.95)));
        assertThat(percentile(http, 0.95)).isLessThan(LIMIT_MS);

        Path out = Path.of("target", "perf", "analytics-volume.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, report);
        report.forEach(line -> log.info("[volumen] {}", line));
        if (Boolean.getBoolean("retencion.perf.gatling")) {
            gatling(admin, d);
        }
    }

    /** Filtros variados: cada medición toca otra combinación de programa, semestre y nivel. */
    private static AnalyticsFilter filter(Dataset d) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        UUID program = r.nextBoolean() ? null : ISW;
        Integer semester = r.nextBoolean() ? null : Integer.valueOf(r.nextInt(1, 3));
        AnalyticsFilter.RiskBucket level = r.nextBoolean() ? null : AnalyticsFilter.RiskBucket.values()[r.nextInt(3)];
        return new AnalyticsFilter(program, semester, null, d.termId(), null, null, level);
    }

    private String measure(String name, Runnable query) {
        for (int i = 0; i < 10; i++) {
            query.run();
        }
        List<Double> times = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            long start = System.nanoTime();
            query.run();
            times.add((System.nanoTime() - start) / 1_000_000.0);
        }
        double p95 = percentile(times, 0.95);
        assertThat(p95).as("p95 de '%s' sin caché", name).isLessThan(LIMIT_MS);
        return String.format("%s (sin caché): p50 %.1f ms, p95 %.1f ms", name, percentile(times, 0.5), p95);
    }

    private String explain(String name, String sql, Object... args) {
        return name + ":\n  " + String.join("\n  ", jdbc.queryForList("EXPLAIN " + sql, String.class, args));
    }

    private static double percentile(List<Double> values, double p) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get((int) Math.ceil(p * sorted.size()) - 1);
    }

    private static long seconds(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toSeconds();
    }

    private String loadAttendance(UUID term) {
        long started = System.nanoTime();
        jdbc.queryForObject("SELECT attendance_ensure_partition(?)", String.class, term);
        int rows = jdbc.update("""
                INSERT INTO attendance_records (id, term_id, session_id, enrollment_id, student_id, group_id, status,
                    justification_status, justification_deadline, recorded_at, updated_at)
                SELECT gen_random_uuid(), g.term_id, cs.id, e.id, e.student_id, e.group_id,
                       CASE WHEN r.x < 0.08 THEN 'FALTA' WHEN r.x < 0.12 THEN 'TARDANZA' ELSE 'PRESENTE' END, 'NINGUNA',
                       CASE WHEN r.x < 0.08 THEN cs.session_date + 5 END, now(), now()
                FROM enrollments e
                JOIN course_groups g ON g.id = e.group_id
                JOIN class_sessions cs ON cs.group_id = e.group_id
                CROSS JOIN LATERAL (SELECT random() AS x) r
                WHERE g.term_id = ?
                """, term);
        return "Filas de asistencia: " + rows + "; carga: " + seconds(started) + " s";
    }

    /** Lanza la simulación de {@code perf/} en otra JVM contra este servidor. */
    private void gatling(Actor admin, Dataset d) throws Exception {
        relogin(admin);
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> command = new ArrayList<>(windows ? List.of("cmd", "/c", ".\\mvnw.cmd") : List.of("./mvnw"));
        command.addAll(List.of("-f", "perf/pom.xml", "gatling:test", "-DbaseUrl=http://localhost:" + port,
                "-DauthToken=" + admin.tokens().accessToken(), "-DtermId=" + d.termId(),
                "-Dusers=" + Integer.getInteger("retencion.perf.users", 50),
                "-DdurationSeconds=" + Integer.getInteger("retencion.perf.seconds", 120)));
        File output = Path.of("target", "perf", "gatling.log").toFile();
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output).start();
        assertThat(process.waitFor(15, TimeUnit.MINUTES)).as("Gatling terminó").isTrue();
        assertThat(process.exitValue()).as("Gatling cumple las aserciones; ver target/perf/gatling.log").isZero();
    }
}
