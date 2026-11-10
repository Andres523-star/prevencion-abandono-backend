package co.edu.retencion.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.support.IntegrationTest;

/**
 * Pruebas de autorización negativas sobre <b>todos</b> los endpoints: recorre la especificación OpenAPI y
 * llama cada operación sin token (debe responder 401 salvo las rutas públicas declaradas) y con usuarios
 * sin rol y con rol ESTUDIANTE (nunca 2xx fuera de lo permitido, nunca 5xx). Un endpoint nuevo entra solo en
 * la prueba: si queda abierto por error, la prueba falla.
 */
class SecurityMatrixIT extends IntegrationTest {

    /** Rutas que responden sin JWT (cada una con su propio control: firma, token de un solo uso...). */
    private static final Set<String> PUBLIC = Set.of(
            "POST /api/v1/auth/supabase/exchange", "POST /api/v1/auth/refresh", "POST /api/v1/auth/break-glass/login",
            "GET /api/v1/files/{token}", "POST /api/v1/mail/bounces");

    /** Lo único que un usuario autenticado sin roles puede hacer con éxito. */
    private static final Set<String> ANY_AUTHENTICATED = Set.of(
            "GET /api/v1/users/me", "GET /api/v1/notifications", "GET /api/v1/notifications/unread-count",
            "POST /api/v1/notifications/read-all", "GET /api/v1/privacy/processing-activities", "POST /api/v1/auth/logout",
            "GET /api/v1/programs", "GET /api/v1/programs/{id}/subjects", "GET /api/v1/terms", "GET /api/v1/holidays",
            "GET /api/v1/dropout-causes");

    /** Lo que además puede el ESTUDIANTE (sobre sí mismo). */
    private static final Set<String> STUDENT = Set.of(
            "GET /api/v1/students/me", "GET /api/v1/me/justifications", "GET /api/v1/students/me/consents",
            "GET /api/v1/students/me/data-requests", "GET /api/v1/chats", "GET /api/v1/portal/overview",
            "GET /api/v1/portal/absences", "GET /api/v1/portal/support", "GET /api/v1/support-resources");

    private static final Pattern VARIABLE = Pattern.compile("\\{([^}]+)}");

    private record Operation(String method, String path) {
        String key() {
            return method + " " + path;
        }
    }

    @Test
    void everyEndpointRejectsAnonymousAndUnauthorizedCallers() throws Exception {
        Tokens admin = loginAs(Set.of(Role.ADMIN), Set.of());
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/api-docs")
                .header("Authorization", bearer(admin))).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        JsonNode spec = json.readTree(response.getContentAsString());
        assertThat(spec.has("paths")).as(response.getContentAsString().substring(0, Math.min(500, response.getContentAsString().length()))).isTrue();
        List<Operation> operations = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> paths = spec.get("paths").fields(); paths.hasNext(); ) {
            Map.Entry<String, JsonNode> path = paths.next();
            for (Iterator<String> methods = path.getValue().fieldNames(); methods.hasNext(); ) {
                String method = methods.next().toUpperCase();
                if (Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) {
                    operations.add(new Operation(method, path.getKey()));
                }
            }
        }
        assertThat(operations).as("la especificación tiene los endpoints de todas las fases").hasSizeGreaterThan(150);

        Tokens nobody = loginAs(Set.of(), Set.of());
        Tokens student = loginAs(Set.of(Role.ESTUDIANTE), Set.of());
        List<String> problems = new ArrayList<>();
        for (Operation op : operations) {
            int anonymous = call(op, null);
            if (PUBLIC.contains(op.key())) {
                if (anonymous >= 500) {
                    problems.add(op.key() + " sin token respondió " + anonymous);
                }
            } else if (anonymous != 401) {
                problems.add(op.key() + " sin token respondió " + anonymous + " (se esperaba 401)");
            }
            if (PUBLIC.contains(op.key())) {
                continue;
            }
            check(op, "sin rol", call(op, nobody), ANY_AUTHENTICATED, problems);
            Set<String> studentAllowed = new java.util.HashSet<>(ANY_AUTHENTICATED);
            studentAllowed.addAll(STUDENT);
            check(op, "estudiante", call(op, student), studentAllowed, problems);
        }
        assertThat(problems).as(String.join("\n", problems)).isEmpty();
    }

    /** Un usuario sin permiso nunca recibe 2xx ni provoca 5xx; si la ruta no lleva cuerpo, recibe 403 o 404. */
    private static void check(Operation op, String who, int status, Set<String> allowed, List<String> problems) {
        if (status >= 500) {
            problems.add(op.key() + " (" + who + ") provocó " + status);
        } else if (status < 300 && !allowed.contains(op.key())) {
            problems.add(op.key() + " (" + who + ") respondió " + status + ": debería estar prohibido");
        } else if (status == 401) {
            problems.add(op.key() + " (" + who + ") respondió 401 con un token válido");
        }
    }

    private int call(Operation op, Tokens tokens) throws Exception {
        Matcher m = VARIABLE.matcher(op.path());
        StringBuilder uri = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value = name.toLowerCase().contains("year") || name.equals("año") ? "2026"
                    : name.toLowerCase().contains("key") || name.toLowerCase().contains("code")
                            || name.toLowerCase().contains("version") || name.equals("token") ? "x"
                            : UUID.randomUUID().toString();
            m.appendReplacement(uri, value);
        }
        m.appendTail(uri);
        MockHttpServletRequestBuilder request = request(HttpMethod.valueOf(op.method()), uri.toString());
        if (tokens != null) {
            request.header("Authorization", bearer(tokens));
        }
        if (Set.of("POST", "PUT", "PATCH").contains(op.method())) {
            request.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }
}
