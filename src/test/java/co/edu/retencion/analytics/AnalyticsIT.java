package co.edu.retencion.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.support.AnalyticsDataset.Dataset;
import co.edu.retencion.support.AnalyticsIntegrationTest;
import co.edu.retencion.support.AttendanceWorld;
import co.edu.retencion.support.AttendanceWorld.Actor;

/**
 * Endpoints de analítica con 1.200 estudiantes sintéticos: cada cifra se compara con las tablas base,
 * más filtros, supresión de celdas, ABAC, caché y series históricas.
 */
class AnalyticsIT extends AnalyticsIntegrationTest {

    private static final String IN_TERM = "s.id IN (SELECT student_id FROM enrollments WHERE term_id = ?)";

    private static JsonNode rowOf(JsonNode rows, String field, String value) {
        for (JsonNode row : rows) {
            if (value.equals(row.get(field).asText())) {
                return row;
            }
        }
        throw new AssertionError("Sin fila " + field + "=" + value + " en " + rows);
    }

    @Test
    void dropoutByProgramMatchesTheBaseTables() throws Exception {
        Dataset d = dataset();
        Actor admin = admin();
        JsonNode rows = rows(admin, "dropout-by-program", "termId", d.termId().toString()).get("rows");
        assertThat(rows).hasSize(6);
        JsonNode isw = rowOf(rows, "programId", ISW.toString());
        long students = count("SELECT count(*) FROM students s WHERE s.program_id = ? AND " + IN_TERM, ISW, d.termId());
        long retired = count("SELECT count(*) FROM students s WHERE s.status = 'RETIRADO' AND s.program_id = ? AND " + IN_TERM,
                ISW, d.termId());
        assertThat(isw.get("programCode").asText()).isEqualTo("ISW");
        assertThat(isw.get("students").asLong()).isEqualTo(students);
        assertThat(isw.get("dropouts").asLong()).isEqualTo(retired);
        assertThat(isw.get("dropoutRate").decimalValue().doubleValue()).isCloseTo(retired * 100.0 / students,
                org.assertj.core.data.Offset.offset(0.01));

        // Los filtros se combinan: un solo programa, semestre y nivel.
        JsonNode one = rows(admin, "dropout-by-program", "termId", d.termId().toString(), "programId", ISW.toString(),
                "semester", "1", "riskLevel", "BAJO").get("rows");
        assertThat(one).hasSize(1);
        long filtered = count("SELECT count(*) FROM students s JOIN risk_student_state r ON r.student_id = s.id "
                + "WHERE s.program_id = ? AND s.current_semester = 1 AND r.last_level = 'BAJO' AND " + IN_TERM, ISW, d.termId());
        assertThat(one.get(0).get("students").asLong()).isEqualTo(filtered);
    }

    @Test
    void semestersRiskAttendanceAndFunnelMatchTheBaseTables() throws Exception {
        Dataset d = dataset();
        Actor admin = admin();
        String term = d.termId().toString();

        JsonNode semesters = rows(admin, "by-semester", "termId", term).get("rows");
        assertThat(semesters).isNotEmpty();
        JsonNode first = rowOf(semesters, "semester", "1");
        assertThat(first.get("students").asLong()).isEqualTo(
                count("SELECT count(*) FROM students s WHERE s.current_semester = 1 AND " + IN_TERM, d.termId()));
        assertThat(first.get("atRisk").asLong()).isEqualTo(count("SELECT count(*) FROM students s JOIN risk_student_state r "
                + "ON r.student_id = s.id WHERE s.status = 'ACTIVO' AND s.current_semester = 1 AND r.last_level IN ('ALTO', 'MEDIO') AND "
                + IN_TERM, d.termId()));

        JsonNode risk = rows(admin, "risk-distribution", "termId", term).get("rows");
        assertThat(risk.findValuesAsText("level")).containsExactly("ALTO", "MEDIO", "BAJO", "SIN_ANALISIS");
        assertThat(rowOf(risk, "level", "ALTO").get("students").asLong()).isEqualTo(count("SELECT count(*) FROM students s JOIN "
                + "risk_student_state r ON r.student_id = s.id WHERE s.status = 'ACTIVO' AND r.last_level = 'ALTO' AND " + IN_TERM,
                d.termId()));

        JsonNode attendance = rows(admin, "attendance-risk", "termId", term, "limit", "5");
        JsonNode subjects = attendance.get("subjects");
        assertThat(subjects).hasSize(5);
        assertThat(subjects.get(0).get("absences").asLong()).isGreaterThanOrEqualTo(subjects.get(4).get("absences").asLong());
        assertThat(attendance.at("/nearLimit/critical").asLong()).isEqualTo(count("SELECT count(*) FROM absence_counters c "
                + "JOIN enrollments e ON e.id = c.enrollment_id WHERE c.term_id = ? AND c.level = 'CRITICO' AND e.status = 'ACTIVA'",
                d.termId()));

        JsonNode funnel = rows(admin, "retention-funnel", "termId", term);
        long withdrawn = count("SELECT count(*) FROM retention_cases WHERE term_id = ? AND outcome = 'RETIRADO'", d.termId());
        assertThat(funnel.get("detected").asLong()).isEqualTo(withdrawn);
        assertThat(funnel.get("withdrawn").asLong()).isEqualTo(withdrawn);
        assertThat(funnel.get("retained").asLong()).isZero();
    }

    @Test
    void trendJoinsLiveAndHistoricalSeries() throws Exception {
        Dataset d = dataset();
        Actor admin = admin();
        String csv = """
                programa,anio,mes,semestre,matriculados,desertores,periodo
                ISW,2019,2,,400,40,2019-1
                ISW,2019,8,,380,30,2019-2
                MED,2019,2,,500,25,2019-1
                """;
        JsonNode dry = importHistory(admin, csv, true);
        assertThat(dry.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM dropout_history WHERE EXTRACT(YEAR FROM period_month) = 2019")).isZero();
        importHistory(admin, csv, false);
        assertThat(count("SELECT count(*) FROM dropout_history WHERE EXTRACT(YEAR FROM period_month) = 2019")).isEqualTo(3);
        importHistory(admin, csv, false);
        assertThat(count("SELECT count(*) FROM dropout_history WHERE EXTRACT(YEAR FROM period_month) = 2019"))
                .as("reimportar no duplica").isEqualTo(3);

        JsonNode byYear = rows(admin, "trend", "granularity", "year", "year", "2019", "programId", ISW.toString()).get("rows");
        assertThat(byYear).hasSize(1);
        assertThat(byYear.get(0).get("period").asText()).isEqualTo("2019");
        assertThat(byYear.get(0).get("dropouts").asLong()).isEqualTo(70);
        assertThat(byYear.get(0).get("enrolled").asLong()).isEqualTo(780);
        assertThat(byYear.get(0).get("source").asText()).isEqualTo("HISTORICAL");

        JsonNode byMonth = rows(admin, "trend", "granularity", "month", "year", "2019").get("rows");
        assertThat(byMonth.findValuesAsText("period")).containsExactly("2019-02", "2019-08");
        assertThat(byMonth.get(0).get("dropouts").asLong()).isEqualTo(65);

        // El histórico no tiene nivel de riesgo ni periodo: con esos filtros no aparece.
        assertThat(rows(admin, "trend", "year", "2019", "riskLevel", "ALTO").get("rows")).isEmpty();

        // La serie en vivo viene de los retiros del periodo sintético.
        JsonNode live = rows(admin, "trend", "granularity", "year", "termId", d.termId().toString()).get("rows");
        assertThat(live).isNotEmpty();
        assertThat(live.get(0).get("source").asText()).isEqualTo("LIVE");
        assertThat(live.get(0).get("enrolled").asLong())
                .isEqualTo(count("SELECT count(DISTINCT student_id) FROM enrollments WHERE term_id = ?", d.termId()));
    }

    private JsonNode importHistory(Actor admin, String csv, boolean dryRun) throws Exception {
        return read(mvc.perform(multipart("/api/v1/imports")
                        .file(new MockMultipartFile("file", "historico.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .param("type", "DROPOUT_HISTORY").param("dryRun", String.valueOf(dryRun))
                        .header("Authorization", bearer(admin.tokens())))
                .andExpect(status().is2xxSuccessful()));
    }

    @Test
    void historyImportReportsBadRows() throws Exception {
        Actor admin = admin();
        String csv = """
                programa,anio,matriculados,desertores
                NOEXISTE,2018,100,10
                ISW,2018,10,20
                """;
        JsonNode result = importHistory(admin, csv, true);
        assertThat(result.get("errorRows").asInt()).isEqualTo(2);
    }

    @Test
    void smallCellsAreSuppressed() throws Exception {
        AttendanceWorld w = world(ISW);
        Actor admin = admin();
        refresh();
        String term = worldTerm(w);

        JsonNode program = rows(admin, "dropout-by-program", "termId", term);
        assertThat(program.get("minCellSize").asInt()).isEqualTo(10);
        JsonNode row = program.get("rows").get(0);
        assertThat(row.get("suppressed").asBoolean()).isTrue();
        assertThat(row.get("students").isNull()).isTrue();
        assertThat(row.get("dropouts").isNull()).isTrue();
        assertThat(row.get("dropoutRate").isNull()).isTrue();

        JsonNode semester = rows(admin, "by-semester", "termId", term).get("rows").get(0);
        assertThat(semester.get("suppressed").asBoolean()).isTrue();
        assertThat(semester.get("students").isNull()).isTrue();

        JsonNode risk = rows(admin, "risk-distribution", "termId", term).get("rows");
        assertThat(rowOf(risk, "level", "SIN_ANALISIS").get("students").isNull()).isTrue();

        JsonNode subject = rows(admin, "attendance-risk", "termId", term).get("subjects").get(0);
        assertThat(subject.get("enrollments").isNull()).isTrue();
        assertThat(subject.get("absences").isNull()).isTrue();

        // El umbral es configurable y aplica también a un grupo grande.
        Dataset d = dataset();
        setting("analytics.min-cell-size", 100_000);
        JsonNode all = rows(admin, "dropout-by-program", "termId", d.termId().toString());
        assertThat(all.get("minCellSize").asInt()).isEqualTo(100_000);
        assertThat(all.get("rows").findValuesAsText("suppressed")).containsOnly("true");
    }

    private String worldTerm(AttendanceWorld w) {
        return jdbc.queryForObject("SELECT term_id FROM course_groups WHERE id = ?", UUID.class, w.groupId()).toString();
    }

    @Test
    void scopeIsEnforcedPerProgramAndRole() throws Exception {
        Dataset d = dataset();
        String term = d.termId().toString();
        Actor isw = staff(Set.of(Role.COORDINADOR), Set.of(ISW));
        Actor med = staff(Set.of(Role.COORDINADOR), Set.of(MED));
        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of(ISW));
        Actor noPrograms = staff(Set.of(Role.CONSEJERO), Set.of());
        Actor psychologist = staff(Set.of(Role.PSICOLOGO), Set.of());
        Actor teacher = staff(Set.of(Role.DOCENTE), Set.of());

        // Misma consulta, distinto alcance: la caché no se comparte entre usuarios de distinto programa.
        JsonNode mine = rows(isw, "dropout-by-program", "termId", term).get("rows");
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("programCode").asText()).isEqualTo("ISW");
        JsonNode theirs = rows(med, "dropout-by-program", "termId", term).get("rows");
        assertThat(theirs).hasSize(1);
        assertThat(theirs.get(0).get("programCode").asText()).isEqualTo("MED");
        assertThat(rows(counselor, "dropout-by-program", "termId", term).get("rows")).hasSize(1);
        assertThat(rows(psychologist, "dropout-by-program", "termId", term).get("rows")).hasSize(6);

        for (String path : List.of("dropout-by-program", "trend", "by-semester", "risk-distribution", "attendance-risk",
                "retention-funnel")) {
            mvc.perform(chart(isw, path).param("programId", MED.toString())).andExpect(status().isForbidden());
            mvc.perform(chart(teacher, path)).andExpect(status().isForbidden());
            mvc.perform(chart(noPrograms, path)).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/analytics/" + path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/analytics/refresh")
                .header("Authorization", bearer(isw.tokens()))).andExpect(status().isForbidden());
    }

    @Test
    void invalidFiltersAreRejected() throws Exception {
        Actor admin = admin();
        mvc.perform(chart(admin, "trend").param("granularity", "week")).andExpect(status().isBadRequest());
        mvc.perform(chart(admin, "by-semester").param("semester", "99")).andExpect(status().isBadRequest());
        mvc.perform(chart(admin, "by-semester").param("riskLevel", "ROJO")).andExpect(status().isBadRequest());
        mvc.perform(chart(admin, "by-semester").param("from", "2026-02-01").param("to", "2026-01-01"))
                .andExpect(status().isBadRequest());
        mvc.perform(chart(admin, "attendance-risk").param("limit", "1000")).andExpect(status().isBadRequest());
    }

    @Test
    void cacheServesUntilTheViewsAreRefreshedAndEventsMarkThemDirty() throws Exception {
        Dataset d = dataset();
        Actor admin = admin();
        String term = d.termId().toString();
        long before = rows(admin, "by-semester", "termId", term).get("rows").get(0).get("students").asLong();

        UUID student = jdbc.queryForObject("SELECT s.id FROM students s WHERE s.status = 'ACTIVO' AND s.current_semester = 1 AND "
                + IN_TERM + " LIMIT 1", UUID.class, d.termId());
        jdbc.update("UPDATE students SET deleted_at = now() WHERE id = ?", student);
        try {
            assertThat(rows(admin, "by-semester", "termId", term).get("rows").get(0).get("students").asLong())
                    .as("responde la caché").isEqualTo(before);
            refresh();
            assertThat(rows(admin, "by-semester", "termId", term).get("rows").get(0).get("students").asLong())
                    .as("el refresco invalida").isEqualTo(before - 1);
        } finally {
            jdbc.update("UPDATE students SET deleted_at = NULL WHERE id = ?", student);
            refresh();
        }

        assertThat(jdbc.queryForObject("SELECT dirty FROM analytics_refresh_state", Boolean.class)).isFalse();
        AttendanceWorld w = world(ISW);
        markAbsence(w, 0);
        awaitListeners();
        assertThat(jdbc.queryForObject("SELECT dirty FROM analytics_refresh_state", Boolean.class))
                .as("una falta marca las vistas para refrescar").isTrue();
        refresh();
    }
}
