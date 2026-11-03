package co.edu.retencion.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.support.IntegrationTest;

/**
 * Importación masiva: plantillas, docentes, materias con prerrequisitos, grupos con horario,
 * estudiantes (10.000 filas en segundo plano), inscripciones, dry-run, idempotencia, reporte sin
 * datos personales, Excel y validación del archivo.
 */
class ImportIT extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private Tokens admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = loginAs(Set.of(Role.ADMIN), Set.of());
    }

    private ResultActions upload(String type, boolean dryRun, String fileName, byte[] content, String mapping)
            throws Exception {
        var request = multipart("/api/v1/imports").file(new MockMultipartFile("file", fileName, "text/csv", content))
                .param("type", type).param("dryRun", Boolean.toString(dryRun))
                .header("Authorization", bearer(admin));
        if (mapping != null) {
            request.param("mapping", mapping);
        }
        return mvc.perform(request);
    }

    private JsonNode uploadCsv(String type, boolean dryRun, String csv) throws Exception {
        return read(upload(type, dryRun, type.toLowerCase() + ".csv", csv.getBytes(StandardCharsets.UTF_8), null)
                .andExpect(status().isOk()));
    }

    private JsonNode waitUntilFinished(String jobId) throws Exception {
        long deadline = System.currentTimeMillis() + 240_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode job = read(mvc.perform(get("/api/v1/imports/" + jobId).header("Authorization", bearer(admin))));
            String status = job.get("status").asText();
            if (status.equals("COMPLETED") || status.equals("FAILED")) {
                return job;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("La importación no terminó a tiempo");
    }

    @Test
    void templatesListCanonicalHeaders() throws Exception {
        String template = mvc.perform(get("/api/v1/imports/templates/STUDENTS").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(template.lines().findFirst().orElseThrow())
                .startsWith("tipo_documento,documento,nombre,correo,telefono,programa,semestre");
        Tokens coordinator = loginAs(Set.of(Role.COORDINADOR), Set.of(ISW));
        mvc.perform(get("/api/v1/imports/templates/GROUPS").header("Authorization", bearer(coordinator)))
                .andExpect(status().isForbidden());
    }

    @Test
    void fullAcademicChainImport() throws Exception {
        String tag = uniqueCode("I").toLowerCase();
        String term = read(mvc.perform(get("/api/v1/terms/" + createTerm(admin)).header("Authorization", bearer(admin))))
                .get("code").asText();

        JsonNode teachers = uploadCsv("TEACHERS", false, "correo,nombre\n"
                + tag + ".profe1@universidad.edu.co,Profe Uno\n" + tag + ".profe2@universidad.edu.co,Profe Dos\n");
        assertThat(teachers.get("createdRows").asInt()).isEqualTo(2);

        String s1 = tag.toUpperCase() + "-101";
        String s2 = tag.toUpperCase() + "-201";
        JsonNode subjects = uploadCsv("SUBJECTS", false, "programa;codigo;nombre;creditos;semestre_sugerido;prerrequisitos\n"
                + "ISW;" + s1 + ";Programación I;4;1;\n" + "ISW;" + s2 + ";Programación II;4;2;" + s1 + "\n");
        assertThat(subjects.get("createdRows").asInt()).isEqualTo(2);

        JsonNode groups = uploadCsv("GROUPS", false, "programa,materia,periodo,grupo,cupo,correo_docente,tipo,horario\n"
                + "ISW," + s1 + "," + term + ",A,40," + tag + ".profe1@universidad.edu.co,REGULAR,\"LUN 07:00-09:00 A-1; MIE 07:00-09:00 A-1\"\n"
                + "ISW," + s2 + "," + term + ",A,40," + tag + ".profe2@universidad.edu.co,,\n"
                + "ISW,NO-EXISTE," + term + ",A,40,,,\n");
        assertThat(groups.get("createdRows").asInt()).isEqualTo(2);
        assertThat(groups.get("errorRows").asInt()).isEqualTo(1);

        String doc = String.valueOf(7_000_000_000L + (long) (Math.random() * 999_999_999L));
        uploadCsv("STUDENTS", false, "tipo_documento,documento,nombre,correo,programa,semestre,fecha_ingreso\n"
                + "CC," + doc + ",Laura Gómez,laura." + tag + "@gmail.com,ISW,1,2026-02-01\n");
        JsonNode enrollments = uploadCsv("ENROLLMENTS", false, "documento,programa,materia,periodo,grupo\n"
                + doc + ",ISW," + s1 + "," + term + ",A\n" + doc + ",ISW," + s1 + "," + term + ",A\n");
        assertThat(enrollments.get("createdRows").asInt()).isEqualTo(1);
        assertThat(enrollments.get("errorRows").asInt()).as("fila repetida en el archivo").isEqualTo(1);

        // Reimportar los mismos materias: idempotente.
        JsonNode again = uploadCsv("SUBJECTS", false, "programa;codigo;nombre;creditos;semestre_sugerido;prerrequisitos\n"
                + "ISW;" + s1 + ";Programación I;4;1;\n" + "ISW;" + s2 + ";Programación II;4;2;" + s1 + "\n");
        assertThat(again.get("unchangedRows").asInt()).isEqualTo(2);
    }

    @Test
    void tenThousandStudentsAreImportedInBackgroundAndReimportIsIdempotent() throws Exception {
        StringBuilder csv = new StringBuilder(
                "tipo_documento,documento,nombre,correo,telefono,programa,semestre,estado,fecha_ingreso,estrato,trabaja,personas_a_cargo,transporte\n");
        String prefix = String.valueOf(10 + (int) (Math.random() * 89));
        String[] programs = {"ENF", "MED", "DER", "ISW", "IIN", "ODO"};
        for (int i = 0; i < 10_000; i++) {
            csv.append("CC,").append(prefix).append(String.format("%08d", i)).append(",Estudiante ").append(i)
                    .append(",est").append(prefix).append(i).append("@correo.test,300").append(String.format("%07d", i))
                    .append(',').append(programs[i % 6]).append(',').append(1 + i % 10).append(",ACTIVO,2025-08-01,")
                    .append(1 + i % 6).append(',').append(i % 3 == 0 ? "si" : "no").append(',').append(i % 4)
                    .append(",TRANSPORTE_PUBLICO\n");
        }
        byte[] content = csv.toString().getBytes(StandardCharsets.UTF_8);
        assertThat(content.length).as("supera el umbral síncrono").isGreaterThan(256 * 1024);

        JsonNode queued = read(upload("STUDENTS", false, "estudiantes.csv", content, null)
                .andExpect(status().isAccepted()));
        JsonNode done = waitUntilFinished(queued.get("id").asText());
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.get("totalRows").asInt()).isEqualTo(10_000);
        assertThat(done.get("createdRows").asInt()).isEqualTo(10_000);
        assertThat(done.get("errorRows").asInt()).isZero();

        Integer files = jdbc.queryForObject("SELECT count(*) FROM import_files WHERE job_id = ?", Integer.class,
                UUID.fromString(done.get("id").asText()));
        assertThat(files).as("el archivo con datos personales se borra").isZero();

        // Reimportación de una muestra: sin duplicados.
        String sample = csv.toString().lines().limit(201).reduce((a, b) -> a + "\n" + b).orElseThrow() + "\n";
        JsonNode reimport = uploadCsv("STUDENTS", false, sample);
        assertThat(reimport.get("unchangedRows").asInt()).isEqualTo(200);
        assertThat(reimport.get("createdRows").asInt()).isZero();
    }

    @Test
    void dryRunValidatesWithoutWritingAndReportHasNoPersonalData() throws Exception {
        String doc = String.valueOf(6_000_000_000L + (long) (Math.random() * 999_999_999L));
        String csv = "tipo_documento,documento,nombre,correo,programa,semestre,fecha_ingreso\n"
                + "CC," + doc + ",Pedro Pérez,pedro.perez@gmail.com,MED,3,2025-02-01\n"
                + "XX,9876543210,María Secreta,maria.secreta@gmail.com,NOPE,40,ayer\n";
        JsonNode dry = uploadCsv("STUDENTS", true, csv);
        assertThat(dry.get("validRows").asInt()).isEqualTo(1);
        assertThat(dry.get("errorRows").asInt()).isEqualTo(1);

        String report = mvc.perform(get("/api/v1/imports/" + dry.get("id").asText() + "/report")
                .header("Authorization", bearer(admin))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(report).contains("fila,resultado,mensaje", "2,VALID", "3,ERROR", "programa: no existe",
                        "semestre: debe estar entre 1 y 14", "fecha_ingreso")
                .doesNotContain("María").doesNotContain("9876543210").doesNotContain("maria.secreta");
        mvc.perform(get("/api/v1/students/search").param("document", doc).header("Authorization", bearer(admin))
                .header("X-Access-Reason", "verificación")).andExpect(status().isNotFound());
    }

    @Test
    void configurableMappingAndMissingColumns() throws Exception {
        String doc = String.valueOf(5_000_000_000L + (long) (Math.random() * 999_999_999L));
        JsonNode mapped = read(upload("STUDENTS", false, "custom.csv",
                ("Tipo,Num Identificación,Nombre Completo,Carrera,Semestre Actual,Fecha de Ingreso\n"
                        + "CC," + doc + ",Juan Ruiz,DER,1,01/02/2026\n").getBytes(StandardCharsets.UTF_8),
                "{\"tipo_documento\":\"Tipo\",\"documento\":\"Num Identificación\",\"programa\":\"Carrera\"}")
                .andExpect(status().isOk()));
        assertThat(mapped.get("createdRows").asInt()).isEqualTo(1);

        JsonNode failed = uploadCsv("STUDENTS", false, "documento,nombre\n123,Ana\n");
        assertThat(failed.get("status").asText()).isEqualTo("FAILED");
        assertThat(failed.get("failureReason").asText()).contains("tipo_documento", "programa");
    }

    @Test
    void excelFilesAreSupportedAndFakeExcelIsRejected() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFSheet sheet = workbook.createSheet();
        String email = uniqueCode("xl").toLowerCase() + "@universidad.edu.co";
        sheet.createRow(0).createCell(0).setCellValue("correo");
        sheet.getRow(0).createCell(1).setCellValue("nombre");
        sheet.createRow(1).createCell(0).setCellValue(email);
        sheet.getRow(1).createCell(1).setCellValue("Docente Excel");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        workbook.close();

        JsonNode job = read(upload("TEACHERS", false, "docentes.xlsx", out.toByteArray(), null).andExpect(status().isOk()));
        assertThat(job.get("format").asText()).isEqualTo("XLSX");
        assertThat(job.get("createdRows").asInt()).isEqualTo(1);

        upload("TEACHERS", false, "malicioso.xlsx", "correo,nombre\n".getBytes(StandardCharsets.UTF_8), null)
                .andExpect(status().isBadRequest());
        upload("TEACHERS", false, "vacio.csv", new byte[0], null).andExpect(status().isBadRequest());
        upload("TEACHERS", false, "x.csv", "a,b".getBytes(StandardCharsets.UTF_8), "no-es-json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"));
    }
}
