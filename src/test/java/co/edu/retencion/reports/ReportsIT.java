package co.edu.retencion.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.reports.application.ReportWorker;
import co.edu.retencion.students.StudentRegistry;
import co.edu.retencion.support.AnalyticsDataset.Dataset;
import co.edu.retencion.support.AnalyticsIntegrationTest;
import co.edu.retencion.support.AttendanceWorld.Actor;

/** Reportes: cola, formatos, alcance, identidad con motivo, auditoría, descarga y vencimiento. */
class ReportsIT extends AnalyticsIntegrationTest {

    @Autowired
    ReportWorker reports;
    @Autowired
    StudentRegistry students;

    private ResultActions request(Actor actor, String reason, Object... fields) throws Exception {
        Map<String, Object> body = new HashMap<>();
        for (int i = 0; i < fields.length; i += 2) {
            body.put((String) fields[i], fields[i + 1]);
        }
        var builder = post("/api/v1/reports").header("Authorization", bearer(actor.tokens()))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (reason != null) {
            builder.header("X-Access-Reason", reason);
        }
        return mvc.perform(builder);
    }

    private String ready(Actor actor, Object... fields) throws Exception {
        return ready(actor, null, fields);
    }

    private String ready(Actor actor, String reason, Object[] fields, Object... ignored) throws Exception {
        String id = read(request(actor, reason, fields).andExpect(status().isAccepted())).get("id").asText();
        assertThat(reports.drain()).isPositive();
        JsonNode state = read(mvc.perform(get("/api/v1/reports/" + id).header("Authorization", bearer(actor.tokens())))
                .andExpect(status().isOk()));
        assertThat(state.get("status").asText()).isEqualTo("LISTO");
        assertThat(state.get("sizeBytes").asLong()).isPositive();
        assertThat(state.toString()).doesNotContain("reportes/");
        return id;
    }

    private byte[] download(Actor actor, String id) throws Exception {
        JsonNode url = read(mvc.perform(get("/api/v1/reports/" + id + "/download-url")
                .header("Authorization", bearer(actor.tokens()))).andExpect(status().isOk()));
        assertThat(url.get("expiresAt").asText()).isNotBlank();
        assertThat(url.get("fileName").asText()).startsWith("reporte-");
        return mvc.perform(get(URI.create(url.get("url").asText()).getPath())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    private static String text(byte[] xlsx) throws Exception {
        StringBuilder all = new StringBuilder();
        DataFormatter format = new DataFormatter();
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            for (Sheet sheet : book) {
                for (Row row : sheet) {
                    row.forEach(c -> all.append(format.formatCellValue(c)).append('|'));
                    all.append('\n');
                }
            }
        }
        return all.toString();
    }

    @Test
    void generalReportInExcelAndPdfWithConfidentialityNotice() throws Exception {
        Dataset d = dataset();
        Actor admin = admin();
        Object[] fields = {"type", "GENERAL", "format", "XLSX", "termId", d.termId()};
        String xlsx = text(download(admin, ready(admin, null, fields)));
        assertThat(xlsx).contains("CONFIDENCIAL").contains("Ley 1581").contains("Por programa").contains("Ingeniería de Software")
                .contains("Embudo de retención").contains("Materias con más faltas");

        Object[] pdfFields = {"type", "BY_SEMESTER", "format", "PDF", "termId", d.termId()};
        byte[] pdf = download(admin, ready(admin, null, pdfFields));
        PdfReader reader = new PdfReader(pdf);
        String page = new PdfTextExtractor(reader).getTextFromPage(1);
        reader.close();
        assertThat(page).contains("CONFIDENCIAL").contains("Ley 1581").contains("Deserción por semestre");

        Object[] yearFields = {"type", "BY_YEAR", "format", "XLSX"};
        assertThat(text(download(admin, ready(admin, null, yearFields)))).contains("Por año");
        Object[] programFields = {"type", "BY_PROGRAM", "format", "XLSX", "termId", d.termId()};
        assertThat(text(download(admin, ready(admin, null, programFields)))).contains("Por nivel de riesgo");
    }

    @Test
    void identityNeedsAReasonAndIsAudited() throws Exception {
        Dataset d = dataset();
        Actor coordinator = staff(Set.of(Role.COORDINADOR), Set.of(ISW));

        request(coordinator, null, "type", "AT_RISK_STUDENTS", "format", "XLSX", "includeIdentity", true)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("report_reason_required"));
        request(coordinator, "motivo", "type", "GENERAL", "format", "XLSX", "includeIdentity", true)
                .andExpect(status().isBadRequest());

        // Sin identidad: solo códigos, y solo del programa del coordinador.
        Object[] plain = {"type", "AT_RISK_STUDENTS", "format", "XLSX", "riskLevel", "ALTO"};
        String anonymous = text(download(coordinator, ready(coordinator, null, plain)));
        UUID sample = jdbc.queryForObject("""
                SELECT s.id FROM students s JOIN risk_student_state r ON r.student_id = s.id
                WHERE r.last_level = 'ALTO' AND s.status = 'ACTIVO' AND s.program_id = ?
                  AND s.id IN (SELECT student_id FROM enrollments WHERE term_id = ?) LIMIT 1
                """, UUID.class, ISW, d.termId());
        String name = students.displayName(sample).orElseThrow();
        String code = students.findById(sample).orElseThrow().anonCode();
        assertThat(anonymous).contains(code).doesNotContain(name).doesNotContain("|MED|");

        // Con identidad y motivo: nombre, encabezado de confidencialidad y auditoría.
        Object[] named = {"type", "AT_RISK_STUDENTS", "format", "XLSX", "riskLevel", "ALTO", "includeIdentity", true};
        String id = ready(coordinator, "Plan de acompañamiento", named);
        String identified = text(download(coordinator, id));
        assertThat(identified).contains(code).contains(name).contains("Contiene identidad");
        String lower = identified.toLowerCase();
        assertThat(lower).doesNotContain("estrato").doesNotContain("socioecon").doesNotContain("psicol").doesNotContain("salud");

        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'REPORT_REQUEST' AND entity_id = ? "
                + "AND reason = 'Plan de acompañamiento'", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'REPORT_GENERATED' AND entity_id = ? AND actor_user_id = ?",
                id, coordinator.userId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_log WHERE entity_type = 'Report' AND entity_id = ? "
                + "AND action NOT IN ('REPORT_REQUEST', 'REPORT_GENERATED')", id)).as("descarga auditada").isPositive();
    }

    @Test
    void attendanceReportListsStudentsNearTheLimit() throws Exception {
        Dataset d = dataset();
        Actor admin = admin();
        Object[] fields = {"type", "ATTENDANCE", "format", "XLSX", "termId", d.termId(), "programId", ISW};
        String content = text(download(admin, ready(admin, null, fields)));
        assertThat(content).contains("Materias con más faltas").contains("Cerca del límite de faltas").contains("CRITICO")
                .contains("EST-");
        long expected = count("""
                SELECT count(*) FROM absence_counters c JOIN students s ON s.id = c.student_id
                WHERE c.term_id = ? AND c.level <> 'NINGUNO' AND s.program_id = ?
                """, d.termId(), ISW);
        assertThat(content.lines().filter(l -> l.startsWith("EST-")).count()).isEqualTo(expected);
    }

    @Test
    void reportsAreOnlyVisibleToWhoeverAskedAndWithinScope() throws Exception {
        Actor coordinator = staff(Set.of(Role.COORDINADOR), Set.of(ISW));
        Actor other = staff(Set.of(Role.COORDINADOR), Set.of(ISW));
        Actor admin = admin();
        Actor teacher = staff(Set.of(Role.DOCENTE), Set.of());

        request(coordinator, null, "type", "GENERAL", "format", "PDF", "programId", MED).andExpect(status().isForbidden());
        request(teacher, null, "type", "GENERAL", "format", "PDF").andExpect(status().isForbidden());
        request(coordinator, null, "format", "PDF").andExpect(status().isBadRequest());

        Object[] fields = {"type", "BY_PROGRAM", "format", "XLSX"};
        String id = ready(coordinator, null, fields);
        mvc.perform(get("/api/v1/reports/" + id).header("Authorization", bearer(other.tokens()))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/reports/" + id + "/download-url").header("Authorization", bearer(other.tokens())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/reports/" + id).header("Authorization", bearer(admin.tokens()))).andExpect(status().isOk());
        JsonNode mine = read(mvc.perform(get("/api/v1/reports").header("Authorization", bearer(coordinator.tokens())))
                .andExpect(status().isOk()));
        assertThat(mine.get("items").findValuesAsText("id")).contains(id);

        // El reporte se generó con el alcance de quien lo pidió, sin importar quién corre el trabajo.
        String content = text(download(coordinator, id));
        for (String program : List.of("Medicina", "Derecho", "Odontología")) {
            assertThat(content).doesNotContain(program);
        }
    }

    @Test
    void failuresExpiryAndStates() throws Exception {
        dataset();
        Actor admin = admin();

        // Todavía sin generar.
        String pending = read(request(admin, null, "type", "GENERAL", "format", "XLSX").andExpect(status().isAccepted()))
                .get("id").asText();
        mvc.perform(get("/api/v1/reports/" + pending + "/download-url").header("Authorization", bearer(admin.tokens())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("report_not_ready"));
        reports.drain();

        // Un PDF demasiado grande falla con un código claro.
        setting("reports.pdf-max-rows", 1);
        String large = read(request(admin, null, "type", "AT_RISK_STUDENTS", "format", "PDF").andExpect(status().isAccepted()))
                .get("id").asText();
        reports.drain();
        JsonNode failed = read(mvc.perform(get("/api/v1/reports/" + large).header("Authorization", bearer(admin.tokens()))));
        assertThat(failed.get("status").asText()).isEqualTo("FALLIDO");
        assertThat(failed.get("failureCode").asText()).isEqualTo("report_too_large");

        // Vencido: el archivo se borra y la descarga responde 410.
        clock.setNow(clock.instant().plus(Duration.ofHours(25)));
        relogin(admin);
        mvc.perform(get("/api/v1/reports/" + pending + "/download-url").header("Authorization", bearer(admin.tokens())))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("report_expired"));
        assertThat(reports.expireDue()).isPositive();
        assertThat(jdbc.queryForObject("SELECT status FROM report_jobs WHERE id = ?", String.class, UUID.fromString(pending)))
                .isEqualTo("EXPIRADO");
        assertThat(jdbc.queryForObject("SELECT object_key FROM report_jobs WHERE id = ?", String.class, UUID.fromString(pending)))
                .isNull();
        mvc.perform(get("/api/v1/reports/" + pending + "/download-url").header("Authorization", bearer(admin.tokens())))
                .andExpect(status().isGone());
    }
}
