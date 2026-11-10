package retencion;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonFile;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;

/**
 * Pico de asistencia: cada usuario virtual es un docente tomando lista de una sesión del mismo día, todos
 * dentro de {@code rampSeconds} (la franja de "la misma hora"). Los datos los arma {@code AttendancePeakIT}.
 *
 * <p>Parámetros: {@code baseUrl}, {@code feeder} (JSON con token, ruta y cuerpo por sesión), {@code users},
 * {@code rampSeconds} y {@code p95Ms}.
 */
public class AttendancePeakSimulation extends Simulation {

    private static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8080");
    private static final String FEEDER = locate(System.getProperty("feeder", "target/perf/attendance-peak.json"));
    private static final int USERS = Integer.getInteger("users", 100);
    private static final int RAMP = Integer.getInteger("rampSeconds", 60);
    private static final int P95 = Integer.getInteger("p95Ms", 3000);

    /**
     * Ruta absoluta del archivo de datos. Se pasa relativa (sin espacios: Maven no propaga en Windows una
     * propiedad con espacios) y aquí se busca desde el directorio de trabajo y su padre.
     */
    private static String locate(String relative) {
        java.nio.file.Path cwd = java.nio.file.Path.of(System.getProperty("user.dir"));
        for (java.nio.file.Path candidate : new java.nio.file.Path[] {java.nio.file.Path.of(relative), cwd.resolve(relative),
                cwd.getParent() == null ? cwd.resolve(relative) : cwd.getParent().resolve(relative)}) {
            if (java.nio.file.Files.exists(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }
        throw new IllegalStateException("No se encontró el archivo de datos: " + relative + " (desde " + cwd + ")");
    }

    private final ScenarioBuilder teachers = scenario("Toma de lista")
            .feed(jsonFile(FEEDER).queue())
            .exec(http("asistencia").put("#{path}").header("Authorization", "Bearer #{token}")
                    .header("Content-Type", "application/json").body(StringBody("#{body}")).check(status().is(200)));

    {
        setUp(teachers.injectOpen(rampUsers(USERS).during(RAMP))).protocols(http.baseUrl(BASE_URL))
                .assertions(global().responseTime().percentile(95).lt(P95), global().failedRequests().count().is(0L));
    }
}
