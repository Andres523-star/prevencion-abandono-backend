package retencion;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

/**
 * Carga sobre los endpoints de analítica y el tablero. Combina filtros al azar, como un usuario que
 * hace clic en los gráficos, y exige p95 &lt; 500 ms sin errores.
 *
 * <p>Parámetros: {@code baseUrl}, {@code authToken} (JWT de la plataforma con rol ADMIN), {@code termId},
 * {@code users} (usuarios nuevos por segundo) y {@code durationSeconds}.
 */
public class AnalyticsSimulation extends Simulation {

    private static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8080");
    private static final String TOKEN = System.getProperty("authToken", "");
    private static final String TERM = System.getProperty("termId", "");
    private static final int USERS = Integer.getInteger("users", 50);
    private static final int SECONDS = Integer.getInteger("durationSeconds", 120);

    private static final String[] CHARTS = {"dropout-by-program", "by-semester", "risk-distribution", "attendance-risk",
            "retention-funnel", "trend?granularity=month"};
    private static final String[] LEVELS = {"", "ALTO", "MEDIO", "BAJO"};
    private static final String[] SEMESTERS = {"", "1", "2"};

    private static String randomPath() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String chart = CHARTS[random.nextInt(CHARTS.length)];
        List<String> filters = new ArrayList<>();
        if (!TERM.isEmpty()) {
            filters.add("termId=" + TERM);
        }
        String level = LEVELS[random.nextInt(LEVELS.length)];
        if (!level.isEmpty()) {
            filters.add("riskLevel=" + level);
        }
        String semester = SEMESTERS[random.nextInt(SEMESTERS.length)];
        if (!semester.isEmpty()) {
            filters.add("semester=" + semester);
        }
        return "/api/v1/analytics/" + chart + (chart.contains("?") ? "&" : "?") + String.join("&", filters);
    }

    private final Iterator<Map<String, Object>> paths =
            Stream.generate(() -> Map.<String, Object>of("path", randomPath())).iterator();

    private final HttpProtocolBuilder protocol = http.baseUrl(BASE_URL).acceptHeader("application/json")
            .authorizationHeader("Bearer " + TOKEN);

    private final ScenarioBuilder charts = scenario("Gráficos")
            .feed(paths)
            .exec(http("analitica").get("#{path}").check(status().is(200)))
            .exec(http("tablero").get("/api/v1/dashboard/summary?size=20").check(status().is(200)));

    {
        setUp(charts.injectOpen(constantUsersPerSec(USERS).during(SECONDS))).protocols(protocol)
                .assertions(global().responseTime().percentile(95).lt(500), global().failedRequests().count().is(0L));
    }
}
