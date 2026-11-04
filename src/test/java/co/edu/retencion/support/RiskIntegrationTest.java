package co.edu.retencion.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.risk.application.RiskWorker;
import co.edu.retencion.support.AttendanceWorld.Actor;

/**
 * Utilidades de la Fase 4: Claude simulado, cambio de parámetros por la API de settings (y su
 * restauración al terminar), escenario de riesgo (faltas en nivel crítico y notas bajas) y análisis.
 */
public abstract class RiskIntegrationTest extends RecordsIntegrationTest {

    protected static final FakeClaude CLAUDE = FakeClaude.get();

    @Autowired
    protected RiskWorker worker;
    @Autowired
    protected JdbcTemplate jdbc;

    private final Map<String, JsonNode> originals = new LinkedHashMap<>();
    private Actor settingsAdmin;

    @BeforeEach
    void resetRiskState() {
        CLAUDE.reset();
        // Tareas dejadas por otras pruebas (umbrales de la Fase 3) no deben consumir respuestas simuladas.
        jdbc.update("DELETE FROM risk_work_queue");
    }

    @AfterEach
    void restoreSettings() throws Exception {
        List<Map.Entry<String, JsonNode>> changed = new ArrayList<>(originals.entrySet());
        originals.clear();
        for (Map.Entry<String, JsonNode> e : changed) {
            write(e.getKey(), e.getValue());
        }
    }

    /** Cambia un parámetro con la API real (como lo haría ADMIN, sin redeploy). */
    protected void setting(String key, Object value) throws Exception {
        JsonNode current = read(key);
        originals.putIfAbsent(key, current.get("value"));
        write(key, json.valueToTree(value));
    }

    private JsonNode read(String key) throws Exception {
        admin();
        return read(mvc.perform(get("/api/v1/settings/" + key).header("Authorization", bearer(settingsAdmin.tokens())))
                .andExpect(status().isOk()));
    }

    private void write(String key, JsonNode value) throws Exception {
        JsonNode current = read(key);
        mvc.perform(put("/api/v1/settings/" + key).header("Authorization", bearer(settingsAdmin.tokens()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("value", value, "version", current.get("version").asLong()))))
                .andExpect(status().isOk());
    }

    private void admin() throws Exception {
        if (settingsAdmin == null) {
            settingsAdmin = staff(Set.of(Role.ADMIN), Set.of());
        } else {
            relogin(settingsAdmin);
        }
    }

    /**
     * Escenario de riesgo ALTO: 3 faltas (nivel crítico con la política por defecto) y notas 3.0, 2.0 y
     * 1.5 (promedio bajo, materia bajo 3.0, tendencia a la baja). Trabaja y usa transporte público.
     */
    protected AttendanceWorld highRiskWorld(UUID programId) throws Exception {
        AttendanceWorld w = world(programId);
        for (int i = 0; i < 3; i++) {
            markAbsence(w, i);
        }
        String[] grades = {"3.0", "2.0", "1.5"};
        for (int i = 0; i < grades.length; i++) {
            postJson("/api/v1/enrollments/" + w.enrollmentId() + "/grades", w.teacher().tokens(), Map.of(
                    "value", new java.math.BigDecimal(grades[i]), "assessedOn", w.sessionDate(i).toString(), "description", "Quiz " + (i + 1)))
                    .andExpect(status().isCreated());
        }
        return w;
    }

    protected JsonNode analyze(Actor actor, UUID studentId) throws Exception {
        relogin(actor);
        return read(mvc.perform(post("/api/v1/students/" + studentId + "/analyze")
                .header("Authorization", bearer(actor.tokens()))).andExpect(status().isCreated()));
    }

    protected int assessmentsOf(UUID studentId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM risk_assessments WHERE student_id = ?", Integer.class,
                studentId);
        return n == null ? 0 : n;
    }
}
