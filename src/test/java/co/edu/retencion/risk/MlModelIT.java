package co.edu.retencion.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.risk.domain.MlFeatureVector;
import co.edu.retencion.support.AttendanceWorld;
import co.edu.retencion.support.AttendanceWorld.Actor;
import co.edu.retencion.support.FakeClaude;
import co.edu.retencion.support.OnnxTestModels;
import co.edu.retencion.support.RiskIntegrationTest;

/**
 * Modelo predictivo propio: dataset seudonimizado (ADMIN, auditado), registro de versiones ONNX con
 * revisión de sesgo, comparación antes de aprobar, activación por configuración y vuelta atrás.
 */
class MlModelIT extends RiskIntegrationTest {

    static final List<String> FEATURES = List.of("max_absence_ratio", "subjects_below_passing");

    private ResultActions register(Actor admin, String version, List<String> features, double auc) throws Exception {
        Map<String, Object> metrics = Map.of("auc", auc, "recallHighRisk", 0.70, "brier", 0.15, "ece", 0.03,
                "byGroup", Map.of("1-2", Map.of("auc", auc - 0.01), "3-4", Map.of("auc", auc + 0.01)));
        Map<String, Object> card = new HashMap<>();
        card.put("version", version);
        card.put("algorithm", "logistic-test");
        card.put("trainingTerms", List.of("2023-1", "2023-2", "2024-1"));
        card.put("testTerms", List.of("2024-2"));
        card.put("features", features);
        card.put("metrics", metrics);
        card.put("outputName", "probability");
        byte[] onnx = OnnxTestModels.logistic(new float[] {2.0f, 1.5f}, -1.0f, "probability");
        relogin(admin);
        return mvc.perform(multipart("/api/v1/ml/models")
                .file(new MockMultipartFile("model", "modelo.onnx", "application/octet-stream", onnx))
                .file(new MockMultipartFile("card", "", MediaType.APPLICATION_JSON_VALUE,
                        json.writeValueAsBytes(card)))
                .header("Authorization", bearer(admin.tokens())));
    }

    @Test
    void modelLifecycleFromRegistrationToActivationAndRollback() throws Exception {
        Actor admin = staff(Set.of(Role.ADMIN), Set.of());
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String v1 = "logit-" + suffix + "-1";
        String v2 = "logit-" + suffix + "-2";

        // Revisión de sesgo: un modelo con estrato o variables socioeconómicas se rechaza.
        register(admin, "logit-" + suffix + "-sesgo", List.of("max_absence_ratio", "stratum"), 0.8)
                .andExpect(status().isUnprocessableEntity());
        JsonNode model = read(register(admin, v1, FEATURES, 0.80).andExpect(status().isCreated()));
        assertThat(model.get("status").asText()).isEqualTo("REGISTRADO");
        assertThat(model.get("artifactSha256").asText()).hasSize(64);
        register(admin, v1, FEATURES, 0.80).andExpect(status().isConflict());

        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of(ISW));
        mvc.perform(get("/api/v1/ml/models").header("Authorization", bearer(counselor.tokens())))
                .andExpect(status().isForbidden());

        // Un modelo registrado pero no aprobado no se usa aunque esté en la configuración.
        AttendanceWorld w = highRiskWorld(ISW);
        setting("ai.enabled", false);
        setting("risk.ml.active-version", v1);
        assertThat(analyze(counselor, w.studentId()).get("scoreType").asText()).isEqualTo("RULES");

        relogin(admin);
        JsonNode comparison = read(mvc.perform(get("/api/v1/ml/models/" + v1 + "/comparison")
                .header("Authorization", bearer(admin.tokens()))).andExpect(status().isOk()));
        assertThat(comparison.get("approvable").asBoolean()).isTrue();
        assertThat(read(postJson("/api/v1/ml/models/" + v1 + "/approve", admin.tokens(), Map.of())
                .andExpect(status().isOk())).get("status").asText()).isEqualTo("APROBADO");

        // Activo: probabilidad calibrada del modelo (único caso en que se muestra como probabilidad).
        JsonNode ml = analyze(counselor, w.studentId());
        assertThat(ml.get("scoreType").asText()).isEqualTo("ML_CALIBRATED");
        double expected = 1 / (1 + Math.exp(-(2.0 * 0.75 + 1.5 * 1 - 1.0)));
        assertThat(ml.get("probability").asDouble()).isCloseTo(expected, within(1e-3));
        assertThat(ml.get("level").asText()).isEqualTo("ALTO");
        assertThat(ml.at("/versions/mlModelVersion").asText()).isEqualTo(v1);
        assertThat(ml.get("scoreMeaning").asText()).contains("Probabilidad calibrada");
        assertThat(ml.get("factors").size()).as("toda salida muestra sus factores").isPositive();

        // Un candidato peor no se aprueba sin anulación explícita con motivo.
        register(admin, v2, FEATURES, 0.70).andExpect(status().isCreated());
        JsonNode worse = read(mvc.perform(get("/api/v1/ml/models/" + v2 + "/comparison")
                .header("Authorization", bearer(admin.tokens()))).andExpect(status().isOk()));
        assertThat(worse.get("verdict").asText()).isEqualTo("PEOR");
        assertThat(worse.get("against").asText()).isEqualTo(v1);
        postJson("/api/v1/ml/models/" + v2 + "/approve", admin.tokens(), Map.of()).andExpect(status().isConflict());
        postJson("/api/v1/ml/models/" + v2 + "/approve", admin.tokens(), Map.of("override", true,
                "reason", "Piloto controlado en un programa")).andExpect(status().isOk());

        // Vuelta atrás por configuración: sin modelo activo, de nuevo reglas.
        setting("risk.ml.active-version", "");
        assertThat(analyze(counselor, w.studentId()).get("scoreType").asText()).isEqualTo("RULES");
    }

    @Test
    void datasetExportIsPseudonymizedAdminOnlyAndAudited() throws Exception {
        Actor admin = staff(Set.of(Role.ADMIN), Set.of());
        Actor teacher = staff(Set.of(Role.DOCENTE), Set.of());
        int year = 2000 + (int) (Math.random() * 15);
        String termA = uniqueCode("H-");
        String termB = uniqueCode("H-");
        UUID a = term(admin, termA, year + "-01-12", year + "-05-29");
        UUID b = term(admin, termB, year + "-07-13", year + "-11-27");
        UUID subject = createSubject(admin.tokens(), DER);
        UUID groupA = createGroup(admin.tokens(), subject, a, teacher.userId(), 30);
        UUID groupB = createGroup(admin.tokens(), subject, b, teacher.userId(), 30);
        UUID stays = createStudent(admin.tokens(), DER, null);
        UUID leaves = createStudent(admin.tokens(), DER, null);
        enroll(admin.tokens(), stays, groupA);
        enroll(admin.tokens(), leaves, groupA);
        enroll(admin.tokens(), stays, groupB);
        mvc.perform(put("/api/v1/terms/" + a).header("Authorization", bearer(admin.tokens()))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("code", termA,
                        "name", "Histórico", "startDate", year + "-01-12", "endDate", year + "-05-29", "status", "CLOSED"))))
                .andExpect(status().isOk());

        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of(DER));
        postJson("/api/v1/ml/dataset-exports", counselor.tokens(), Map.of("reason", "x", "termCodes", List.of(termA)))
                .andExpect(status().isForbidden());
        postJson("/api/v1/ml/dataset-exports", admin.tokens(), Map.of("termCodes", List.of(termA)))
                .andExpect(status().isBadRequest());

        var response = postJson("/api/v1/ml/dataset-exports", admin.tokens(), Map.of(
                "reason", "Entrenamiento del modelo 2026-2", "termCodes", List.of(termA)))
                .andExpect(status().isOk()).andReturn().getResponse();
        String csv = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        String[] lines = csv.split("\n");
        assertThat(lines[0]).isEqualTo("row_id,term_code,label_term_code," + String.join(",", MlFeatureVector.FEATURES)
                + ",audit_program,audit_stratum_band,label_dropout");
        assertThat(lines).hasSize(3);
        assertThat(response.getHeader("X-Row-Count")).isEqualTo("2");
        List<String> labels = java.util.Arrays.stream(lines).skip(1).map(l -> l.substring(l.lastIndexOf(',') + 1)).sorted()
                .toList();
        assertThat(labels).containsExactly("0", "1");
        // Seudonimizado: ni ids internos ni anon_code; el estrato solo como banda de auditoría.
        assertThat(csv).doesNotContain(stays.toString(), leaves.toString(), "EST-");
        assertThat(lines[1].split(",")[0]).hasSize(20);
        assertThat(lines[1]).contains(",DER,1-2,");
        Integer audited = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'ML_DATASET_EXPORT'",
                Integer.class);
        assertThat(audited).isPositive();
        Integer exports = jdbc.queryForObject("SELECT count(*) FROM ml_dataset_exports WHERE sha256 = ?", Integer.class,
                response.getHeader("X-Content-Sha256"));
        assertThat(exports).isEqualTo(1);
    }

    @Test
    void biasMetricsAreAggregatedWithSmallCellSuppression() throws Exception {
        AttendanceWorld w = highRiskWorld(ISW);
        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of(ISW));
        CLAUDE.willReturnExplanation("claude-opus-5-5", FakeClaude.validExplanation("ALTO", null));
        analyze(counselor, w.studentId());
        Actor admin = staff(Set.of(Role.ADMIN), Set.of());
        JsonNode report = read(mvc.perform(get("/api/v1/risk/bias-metrics")
                .header("Authorization", bearer(admin.tokens()))).andExpect(status().isOk()));
        assertThat(report.get("minCell").asInt()).isEqualTo(10);
        for (String dimension : List.of("byProgram", "byStratum", "bySemester")) {
            for (JsonNode cell : report.get(dimension)) {
                if (cell.get("suppressed").asBoolean()) {
                    assertThat(cell.get("students").isNull()).isTrue();
                } else {
                    assertThat(cell.get("students").asInt()).isGreaterThanOrEqualTo(10);
                }
            }
        }
        relogin(w.teacher());
        mvc.perform(get("/api/v1/risk/bias-metrics").header("Authorization", bearer(w.teacher().tokens())))
                .andExpect(status().isForbidden());
        Actor coordinator = staff(Set.of(Role.COORDINADOR), Set.of(MED));
        JsonNode scoped = read(mvc.perform(get("/api/v1/risk/bias-metrics")
                .header("Authorization", bearer(coordinator.tokens()))).andExpect(status().isOk()));
        assertThat(scoped.get("byProgram").findValuesAsText("group")).allMatch("MED"::equals);
    }

    private UUID term(Actor admin, String code, String start, String end) throws Exception {
        return UUID.fromString(read(postJson("/api/v1/terms", admin.tokens(), Map.of("code", code, "name", "Histórico",
                "startDate", start, "endDate", end, "status", "ACTIVE")).andExpect(status().isCreated())).get("id").asText());
    }
}
