package co.edu.retencion.students;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.students.domain.AnonCodeGenerator;
import co.edu.retencion.support.IntegrationTest;

/**
 * Estudiantes por API: alta seudonimizada, identidad cifrada y auditada, ABAC (coordinador por
 * programa, docente por grupo, estudiante solo lo suyo), perfil socioeconómico restringido,
 * inscripciones con cupo, consentimiento y vínculo automático con la cuenta del estudiante.
 */
class StudentsIT extends IntegrationTest {

    private static final String REASON = "X-Access-Reason";

    @Autowired
    private JdbcTemplate jdbc;

    private Tokens admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = loginAs(Set.of(Role.ADMIN), Set.of());
    }

    @Test
    void createdStudentIsPseudonymizedAndIdentityEncrypted() throws Exception {
        UUID id = createStudent(admin, MED, null);
        String body = mvc.perform(get("/api/v1/students/" + id).header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode view = json.readTree(body);
        assertThat(AnonCodeGenerator.isValid(view.get("anonCode").asText())).isTrue();
        // El DTO público no lleva identidad.
        assertThat(body).doesNotContain("Estudiante de Prueba").doesNotContain("fullName").doesNotContain("document")
                .doesNotContain("email").doesNotContain("phone").doesNotContain("3001234567");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT full_name_ciphertext, document_ciphertext, phone_ciphertext FROM student_identities WHERE student_id = ?", id);
        assertThat(row.values()).allSatisfy(v -> assertThat((String) v).startsWith("v1.").doesNotContain("Prueba"));
    }

    @Test
    void identityRequiresReasonRoleAndIsAudited() throws Exception {
        UUID id = createStudent(admin, MED, null);
        mvc.perform(get("/api/v1/students/" + id + "/identity").header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("access_reason_required"));
        mvc.perform(get("/api/v1/students/" + id + "/identity").header("Authorization", bearer(admin))
                        .header(REASON, "Verificación de matrícula"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fullName").value("Estudiante de Prueba"));
        Integer audited = jdbc.queryForObject("""
                SELECT count(*) FROM audit_log WHERE action = 'SENSITIVE_READ' AND entity_type = 'StudentIdentity'
                AND entity_id = ? AND outcome = 'SUCCESS' AND reason = 'Verificación de matrícula'
                """, Integer.class, id.toString());
        assertThat(audited).isEqualTo(1);

        Tokens teacher = loginAs(Set.of(Role.DOCENTE), Set.of());
        mvc.perform(get("/api/v1/students/" + id + "/identity").header("Authorization", bearer(teacher))
                .header(REASON, "Curiosidad")).andExpect(status().isForbidden());
    }

    @Test
    void duplicateDocumentIsRejected() throws Exception {
        Map<String, Object> identity = new HashMap<>(Map.of("fullName", "Repetido", "documentType", "CC",
                "documentNumber", "55.444.333-2"));
        Map<String, Object> body = Map.of("identity", identity, "programId", MED, "currentSemester", 1,
                "status", "ACTIVO", "admissionDate", "2026-02-01");
        postJson("/api/v1/students", admin, body).andExpect(status().isCreated());
        identity.put("documentNumber", "554443332"); // mismo documento normalizado
        postJson("/api/v1/students", admin, body).andExpect(status().isConflict());

        mvc.perform(get("/api/v1/students/search").param("document", "55444333-2").header("Authorization", bearer(admin))
                        .header(REASON, "Atención en ventanilla"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.anonCode").exists());
    }

    @Test
    void coordinatorCannotSeeAnotherProgram() throws Exception {
        Tokens coordinator = loginAs(Set.of(Role.COORDINADOR), Set.of(ISW));
        UUID own = createStudent(admin, ISW, null);
        UUID foreign = createStudent(admin, MED, null);

        mvc.perform(get("/api/v1/students/" + own).header("Authorization", bearer(coordinator))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/students/" + foreign).header("Authorization", bearer(coordinator)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/students/" + foreign + "/identity").header("Authorization", bearer(coordinator))
                .header(REASON, "Revisión")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/students").param("programId", MED.toString()).header("Authorization", bearer(coordinator)))
                .andExpect(status().isForbidden());

        JsonNode page = read(mvc.perform(get("/api/v1/students").param("size", "200")
                .header("Authorization", bearer(coordinator))).andExpect(status().isOk()));
        assertThat(page.get("items").findValuesAsText("programId")).containsOnly(ISW.toString());
        postJson("/api/v1/students", coordinator, Map.of("identity", Map.of("fullName", "X", "documentType", "CC",
                "documentNumber", "88776655"), "programId", MED, "currentSemester", 1, "status", "ACTIVO",
                "admissionDate", "2026-02-01")).andExpect(status().isForbidden());
    }

    @Test
    void teacherOnlySeesStudentsOfOwnGroups() throws Exception {
        Tokens teacher = loginAs(Set.of(Role.DOCENTE), Set.of());
        Tokens otherTeacher = loginAs(Set.of(Role.DOCENTE), Set.of());
        UUID term = createTerm(admin);
        UUID group = createGroup(admin, createSubject(admin, ISW), term, teacher.userId(), 30);
        UUID otherGroup = createGroup(admin, createSubject(admin, ISW), term, otherTeacher.userId(), 30);
        UUID student = createStudent(admin, ISW, null);
        UUID notMine = createStudent(admin, ISW, null);
        enroll(admin, student, group);
        enroll(admin, notMine, otherGroup);

        mvc.perform(get("/api/v1/students/" + student).header("Authorization", bearer(teacher))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/students/" + notMine).header("Authorization", bearer(teacher)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/students").header("Authorization", bearer(teacher))).andExpect(status().isForbidden());

        JsonNode roster = read(mvc.perform(get("/api/v1/groups/" + group + "/roster").header("Authorization", bearer(teacher)))
                .andExpect(status().isOk()));
        assertThat(roster.findValuesAsText("studentId")).containsExactly(student.toString());
        assertThat(roster.get(0).get("fullName").asText()).isEqualTo("Estudiante de Prueba");
        mvc.perform(get("/api/v1/groups/" + otherGroup + "/roster").header("Authorization", bearer(teacher)))
                .andExpect(status().isForbidden());
        // El docente nunca ve el perfil socioeconómico.
        mvc.perform(get("/api/v1/students/" + student + "/socioeconomic").header("Authorization", bearer(teacher))
                .header(REASON, "x")).andExpect(status().isForbidden());
    }

    @Test
    void studentAccountIsLinkedByEmailAndSeesOnlyOwnData() throws Exception {
        String sub = UUID.randomUUID().toString();
        String email = "alumna-" + sub.substring(0, 8) + "@gmail.com";
        UUID student = createStudent(admin, ENF, email);
        UUID other = createStudent(admin, ENF, null);

        // Primer ingreso por Supabase con el mismo correo: se vincula y recibe ESTUDIANTE.
        Tokens self = loginWithSupabase(sub, email, "aal1");
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(self)))
                .andExpect(jsonPath("$.roles[0]").value("ESTUDIANTE"));
        mvc.perform(get("/api/v1/students/me").header("Authorization", bearer(self)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.student.id").value(student.toString()))
                .andExpect(jsonPath("$.identity.email").value(email));
        mvc.perform(get("/api/v1/students/" + student).header("Authorization", bearer(self))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/students/" + other).header("Authorization", bearer(self))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/students/" + student + "/enrollments").header("Authorization", bearer(self)))
                .andExpect(status().isOk());

        // Consentimiento desde el portal.
        postJson("/api/v1/students/me/consents", self, Map.of("policyVersion", "2026.1",
                "purpose", "ANALITICA_RETENCION", "granted", true)).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/students/me/consents").header("Authorization", bearer(self)))
                .andExpect(jsonPath("$[0].channel").value("PORTAL"));
    }

    @Test
    void socioeconomicProfileIsRestrictedAndAudited() throws Exception {
        UUID student = createStudent(admin, MED, null);
        Tokens counselor = loginAs(Set.of(Role.CONSEJERO), Set.of(MED));
        Tokens coordinator = loginAs(Set.of(Role.COORDINADOR), Set.of(MED));
        mvc.perform(get("/api/v1/students/" + student + "/socioeconomic").header("Authorization", bearer(counselor))
                        .header(REASON, "Plan de acompañamiento"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stratum").value(2));
        mvc.perform(get("/api/v1/students/" + student + "/socioeconomic").header("Authorization", bearer(coordinator))
                .header(REASON, "x")).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/students/" + student + "/socioeconomic").header("Authorization", bearer(counselor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stratum\":3,\"works\":false,\"dependents\":1,\"transportMode\":\"MOTO\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.transportMode").value("MOTO"));
    }

    @Test
    void enrollmentRespectsCapacityAndCanBeWithdrawn() throws Exception {
        UUID group = createGroup(admin, createSubject(admin, DER), createTerm(admin), null, 1);
        UUID first = createStudent(admin, DER, null);
        UUID second = createStudent(admin, DER, null);
        JsonNode enrollment = read(postJson("/api/v1/students/" + first + "/enrollments", admin, Map.of("groupId", group))
                .andExpect(status().isCreated()));
        postJson("/api/v1/students/" + first + "/enrollments", admin, Map.of("groupId", group))
                .andExpect(status().isCreated()); // idempotente
        postJson("/api/v1/students/" + second + "/enrollments", admin, Map.of("groupId", group))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/v1/enrollments/" + enrollment.get("id").asText() + "/withdraw")
                .header("Authorization", bearer(admin))).andExpect(jsonPath("$.status").value("RETIRADA"));
        postJson("/api/v1/students/" + second + "/enrollments", admin, Map.of("groupId", group))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/students/" + first + "/enrollments").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$[0].status").value("RETIRADA"));
    }

    @Test
    void academicUpdateIdentityRectificationConsentAndSoftDelete() throws Exception {
        UUID student = createStudent(admin, MED, null);
        mvc.perform(put("/api/v1/students/" + student).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"programId\":\"" + MED + "\",\"currentSemester\":5,\"status\":\"APLAZADO\",\"admissionDate\":\"2024-02-01\"}"))
                .andExpect(jsonPath("$.status").value("APLAZADO")).andExpect(jsonPath("$.currentSemester").value(5));
        mvc.perform(put("/api/v1/students/" + student + "/identity").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Nombre Rectificado\",\"documentType\":\"CC\",\"documentNumber\":\"99887766\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fullName").value("Nombre Rectificado"));

        postJson("/api/v1/students/" + student + "/consents", admin, Map.of("policyVersion", "2026.1",
                "purpose", "TRATAMIENTO_ACADEMICO", "granted", true, "channel", "PRESENCIAL")).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/students/" + student + "/consents").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$[0].granted").value(true));

        Tokens user = loginAs(Set.of(), Set.of());
        mvc.perform(put("/api/v1/students/" + student + "/user").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"" + user.userId() + "\"}"))
                .andExpect(jsonPath("$.linkedToUser").value(true));

        mvc.perform(delete("/api/v1/students/" + student).header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/students/" + student).header("Authorization", bearer(admin)))
                .andExpect(status().isForbidden());
        Integer stillThere = jdbc.queryForObject("SELECT count(*) FROM students WHERE id = ? AND deleted_at IS NOT NULL",
                Integer.class, student);
        assertThat(stillThere).as("borrado lógico, no físico").isEqualTo(1);
    }

    @Test
    void listIsKeysetPaginated() throws Exception {
        for (int i = 0; i < 3; i++) {
            createStudent(admin, ISW, null);
        }
        JsonNode first = read(mvc.perform(get("/api/v1/students").param("programId", ISW.toString()).param("size", "2")
                .header("Authorization", bearer(admin))));
        assertThat(first.get("items")).hasSize(2);
        String cursor = first.get("nextCursor").asText();
        JsonNode second = read(mvc.perform(get("/api/v1/students").param("programId", ISW.toString())
                .param("size", "2").param("after", cursor).header("Authorization", bearer(admin))));
        assertThat(second.get("items").get(0).get("id").asText()).isGreaterThan(cursor);
        assertThat(List.of(first, second)).allSatisfy(p -> assertThat(p.toString()).doesNotContain("fullName"));
    }
}
