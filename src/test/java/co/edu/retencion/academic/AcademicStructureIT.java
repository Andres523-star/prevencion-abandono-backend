package co.edu.retencion.academic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

import org.springframework.http.MediaType;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.support.IntegrationTest;

/**
 * Estructura académica por API: programas sembrados, periodos, festivos, materias con
 * prerrequisitos, grupos con horario, generación de sesiones, suspensiones, políticas de
 * asistencia y ABAC (coordinador por programa, docente por grupo).
 */
class AcademicStructureIT extends IntegrationTest {

    private Tokens admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = loginAs(Set.of(Role.ADMIN), Set.of());
    }

    @Test
    void sixProgramsAreSeeded() throws Exception {
        JsonNode programs = read(mvc.perform(get("/api/v1/programs").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()));
        assertThat(programs.findValuesAsText("code")).contains("ENF", "MED", "DER", "ISW", "IIN", "ODO");
        mvc.perform(get("/api/v1/programs/" + MED).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.name").value("Medicina"))
                .andExpect(jsonPath("$.clinical").value(true));
    }

    @Test
    void onlyAdminEditsPrograms() throws Exception {
        Tokens coordinator = loginAs(Set.of(Role.COORDINADOR), Set.of(ISW));
        mvc.perform(put("/api/v1/programs/" + ISW).header("Authorization", bearer(coordinator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ingeniería de Software\",\"clinical\":false,\"active\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/programs/" + ISW).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ingeniería de Software\",\"clinical\":false,\"active\":true}"))
                .andExpect(status().isOk());
    }

    @Test
    void termsAreCreatedListedAndUpdated() throws Exception {
        UUID term = createTerm(admin);
        mvc.perform(get("/api/v1/terms").header("Authorization", bearer(admin))).andExpect(status().isOk());
        JsonNode current = read(mvc.perform(get("/api/v1/terms/" + term).header("Authorization", bearer(admin))));
        mvc.perform(put("/api/v1/terms/" + term).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("code", current.get("code").asText(), "name", "Cerrado",
                                "startDate", "2026-08-03", "endDate", "2026-11-27", "status", "CLOSED"))))
                .andExpect(jsonPath("$.status").value("CLOSED"));
        mvc.perform(put("/api/v1/terms/" + term).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("code", current.get("code").asText(), "name", "Reabierto",
                                "startDate", "2026-08-03", "endDate", "2026-11-27", "status", "ACTIVE"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void subjectPrerequisitesMustExistAndNotFormCycles() throws Exception {
        UUID basic = createSubject(admin, MED);
        JsonNode advanced = read(postJson("/api/v1/subjects", admin, Map.of("programId", MED, "code", uniqueCode("S-"),
                "name", "Avanzada", "credits", 4, "suggestedSemester", 2, "prerequisiteIds", List.of(basic)))
                .andExpect(status().isCreated()));
        assertThat(advanced.get("prerequisiteIds").get(0).asText()).isEqualTo(basic.toString());

        // basic → advanced crearía un ciclo.
        mvc.perform(put("/api/v1/subjects/" + basic).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Básica", "credits", 3, "suggestedSemester", 1,
                                "active", true, "prerequisiteIds", List.of(advanced.get("id").asText())))))
                .andExpect(status().isBadRequest());
        // Prerrequisito de otro programa.
        UUID lawSubject = createSubject(admin, DER);
        postJson("/api/v1/subjects", admin, Map.of("programId", MED, "code", uniqueCode("S-"), "name", "Mixta",
                "credits", 3, "suggestedSemester", 1, "prerequisiteIds", List.of(lawSubject)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/programs/" + MED + "/subjects").header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/subjects/" + basic).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.code").exists());
    }

    @Test
    void coordinatorOnlyManagesOwnProgram() throws Exception {
        Tokens coordinator = loginAs(Set.of(Role.COORDINADOR), Set.of(ISW));
        postJson("/api/v1/subjects", coordinator, Map.of("programId", ISW, "code", uniqueCode("S-"), "name", "Propia",
                "credits", 3, "suggestedSemester", 1, "prerequisiteIds", List.of()))
                .andExpect(status().isCreated());
        postJson("/api/v1/subjects", coordinator, Map.of("programId", MED, "code", uniqueCode("S-"), "name", "Ajena",
                "credits", 3, "suggestedSemester", 1, "prerequisiteIds", List.of()))
                .andExpect(status().isForbidden());
        UUID medSubject = createSubject(admin, MED);
        mvc.perform(put("/api/v1/subjects/" + medSubject + "/attendance-policy").header("Authorization", bearer(coordinator))
                        .contentType(MediaType.APPLICATION_JSON).content(policy(5, 2, 4, 3, null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void groupsValidateTeacherRoleAndClinicalType() throws Exception {
        UUID term = createTerm(admin);
        UUID lawSubject = createSubject(admin, DER);
        Tokens notTeacher = loginAs(Set.of(Role.ESTUDIANTE), Set.of());
        Map<String, Object> body = new HashMap<>(Map.of("subjectId", lawSubject, "termId", term, "code", "A",
                "capacity", 30, "groupType", "REGULAR", "schedule", List.of()));
        body.put("teacherUserId", notTeacher.userId());
        postJson("/api/v1/groups", admin, body).andExpect(status().isBadRequest());
        body.remove("teacherUserId");
        body.put("groupType", "PRACTICA_CLINICA");
        postJson("/api/v1/groups", admin, body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("no admite prácticas")));
    }

    @Test
    void sessionsAreGeneratedFromScheduleSkippingHolidaysAndSuspensions() throws Exception {
        mvc.perform(post("/api/v1/holidays/colombia/2026").header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/holidays").param("year", "2026").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(18)));
        Tokens teacher = loginAs(Set.of(Role.DOCENTE), Set.of());
        UUID term = createTerm(admin);
        UUID group = createGroup(admin, createSubject(admin, ISW), term, teacher.userId(), 30);

        // Lunes y miércoles entre el 3 de agosto y el 27 de noviembre de 2026: 17 + 17 = 34 franjas,
        // menos 4 lunes festivos (7 y 17 ago son viernes/lunes: solo 17 ago, 12 oct, 2 nov, 16 nov) = 30.
        JsonNode generated = read(mvc.perform(post("/api/v1/groups/" + group + "/sessions/generate")
                .header("Authorization", bearer(admin))).andExpect(status().isOk()));
        assertThat(generated.get("created").asInt()).isEqualTo(30);
        JsonNode again = read(mvc.perform(post("/api/v1/groups/" + group + "/sessions/generate")
                .header("Authorization", bearer(admin))));
        assertThat(again.get("created").asInt()).as("idempotente").isZero();

        JsonNode suspension = read(postJson("/api/v1/class-suspensions", admin, Map.of("startDate", "2026-09-07",
                "endDate", "2026-09-09", "programId", ISW, "reason", "Semana de evaluación institucional"))
                .andExpect(status().isCreated()));
        // Cancela al menos las 2 sesiones de este grupo (y las de otros grupos de ISW en esas fechas).
        assertThat(suspension.get("cancelledSessions").asInt()).isGreaterThanOrEqualTo(2);
        mvc.perform(get("/api/v1/class-suspensions").param("from", "2026-09-01").param("to", "2026-09-30")
                .header("Authorization", bearer(admin))).andExpect(status().isOk());

        JsonNode sessions = read(mvc.perform(get("/api/v1/groups/" + group + "/sessions")
                .param("from", "2026-09-07").param("to", "2026-09-09").header("Authorization", bearer(teacher)))
                .andExpect(status().isOk()));
        assertThat(sessions.findValuesAsText("status")).containsOnly("CANCELADA");

        // Un festivo nuevo cancela las sesiones de ese día.
        postJson("/api/v1/holidays", admin, Map.of("date", "2026-09-14", "name", "Festivo local"))
                .andExpect(status().isCreated());
        JsonNode monday = read(mvc.perform(get("/api/v1/groups/" + group + "/sessions")
                .param("from", "2026-09-14").param("to", "2026-09-14").header("Authorization", bearer(admin))));
        assertThat(monday.get(0).get("status").asText()).isEqualTo("CANCELADA");
        mvc.perform(delete("/api/v1/holidays/2026-09-14").header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());
    }

    @Test
    void teacherOfGroupChangesSessionStatusOtherTeachersCannot() throws Exception {
        Tokens teacher = loginAs(Set.of(Role.DOCENTE), Set.of());
        Tokens otherTeacher = loginAs(Set.of(Role.DOCENTE), Set.of());
        UUID group = createGroup(admin, createSubject(admin, ISW), createTerm(admin), teacher.userId(), 30);
        mvc.perform(post("/api/v1/groups/" + group + "/sessions/generate").header("Authorization", bearer(admin)));
        JsonNode sessions = read(mvc.perform(get("/api/v1/groups/" + group + "/sessions")
                .param("from", "2026-08-03").param("to", "2026-08-31").header("Authorization", bearer(teacher))));
        String sessionId = sessions.get(0).get("id").asText();

        mvc.perform(patch("/api/v1/sessions/" + sessionId).header("Authorization", bearer(otherTeacher))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DICTADA\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/groups/" + group).header("Authorization", bearer(otherTeacher)))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/sessions/" + sessionId).header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DICTADA\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DICTADA"));
        mvc.perform(patch("/api/v1/sessions/" + sessionId).header("Authorization", bearer(teacher))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELADA\",\"reason\":\"x\"}"))
                .andExpect(status().isConflict());

        // Listado: el docente solo ve sus grupos.
        JsonNode own = read(mvc.perform(get("/api/v1/groups").header("Authorization", bearer(teacher))));
        assertThat(own.get("items").findValuesAsText("teacherUserId")).containsOnly(teacher.userId().toString());
        JsonNode others = read(mvc.perform(get("/api/v1/groups").header("Authorization", bearer(otherTeacher))));
        assertThat(others.get("items").findValuesAsText("id")).doesNotContain(group.toString());
    }

    @Test
    void scheduleChangeAndTeacherReassignment() throws Exception {
        Tokens teacher = loginAs(Set.of(Role.DOCENTE), Set.of());
        Tokens newTeacher = loginAs(Set.of(Role.DOCENTE), Set.of());
        UUID group = createGroup(admin, createSubject(admin, ISW), createTerm(admin), teacher.userId(), 30);
        mvc.perform(put("/api/v1/groups/" + group + "/schedule").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schedule\":[{\"day\":\"FRIDAY\",\"start\":\"10:00\",\"end\":\"12:00\",\"room\":\"B-2\"}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.schedule.length()").value(1));
        mvc.perform(put("/api/v1/groups/" + group + "/teacher").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teacherUserId\":\"" + newTeacher.userId() + "\"}"))
                .andExpect(jsonPath("$.teacherUserId").value(newTeacher.userId().toString()));
        mvc.perform(get("/api/v1/groups/" + group).header("Authorization", bearer(teacher)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/groups/" + group).header("Authorization", bearer(newTeacher)))
                .andExpect(status().isOk());
    }

    @Test
    void attendancePolicyResolution() throws Exception {
        UUID term = createTerm(admin);
        UUID subject = createSubject(admin, MED);
        UUID regular = createGroup(admin, subject, term, null, 30);

        mvc.perform(get("/api/v1/groups/" + regular + "/attendance-policy").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.maxAbsences").value(4))
                .andExpect(jsonPath("$.warningThreshold").value(2))
                .andExpect(jsonPath("$.criticalThreshold").value(3))
                .andExpect(jsonPath("$.countsPerSession").value(true))
                .andExpect(jsonPath("$.justificationRequiresTeacherApproval").value(true));

        // Política clínica de Medicina: aplica a grupos de práctica, no a los regulares.
        mvc.perform(put("/api/v1/programs/" + MED + "/attendance-policy").param("clinical", "true")
                        .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(policy(3, 1, 2, 2, null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scope").value("PROGRAM_CLINICAL"));
        Map<String, Object> clinicalGroup = new HashMap<>(Map.of("subjectId", subject, "termId", term, "code",
                uniqueCode("R"), "capacity", 12, "groupType", "PRACTICA_CLINICA", "schedule", List.of()));
        UUID rotation = UUID.fromString(read(postJson("/api/v1/groups", admin, clinicalGroup)
                .andExpect(status().isCreated())).get("id").asText());
        mvc.perform(get("/api/v1/groups/" + rotation + "/attendance-policy").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.source").value("PROGRAM_CLINICAL")).andExpect(jsonPath("$.maxAbsences").value(3));
        mvc.perform(get("/api/v1/groups/" + regular + "/attendance-policy").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.source").value("PROGRAM"));

        // La materia sobrescribe al programa (y a la práctica clínica).
        mvc.perform(put("/api/v1/subjects/" + subject + "/attendance-policy").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(policy(6, 3, 5, 5, 3)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/groups/" + rotation + "/attendance-policy").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.source").value("SUBJECT")).andExpect(jsonPath("$.maxAbsences").value(6))
                .andExpect(jsonPath("$.tardiesPerAbsence").value(3));
        mvc.perform(get("/api/v1/subjects/" + subject + "/attendance-policy").header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/subjects/" + subject + "/attendance-policy").header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/groups/" + regular + "/attendance-policy").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.source").value("PROGRAM"));
        mvc.perform(get("/api/v1/programs/" + MED + "/attendance-policy").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.maxAbsences").value(4));

        // Umbrales inválidos y política clínica en un programa no clínico.
        mvc.perform(put("/api/v1/subjects/" + subject + "/attendance-policy").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(policy(4, 3, 3, 3, null)))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/programs/" + DER + "/attendance-policy").param("clinical", "true")
                        .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(policy(4, 2, 3, 3, null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void wholeTermSessionGenerationIsAdminOnly() throws Exception {
        UUID term = createTerm(admin);
        createGroup(admin, createSubject(admin, ISW), term, null, 30);
        Tokens coordinator = loginAs(Set.of(Role.COORDINADOR), Set.of(ISW));
        mvc.perform(post("/api/v1/terms/" + term + "/sessions/generate").header("Authorization", bearer(coordinator)))
                .andExpect(status().isForbidden());
        JsonNode result = read(mvc.perform(post("/api/v1/terms/" + term + "/sessions/generate")
                .header("Authorization", bearer(admin))).andExpect(status().isOk()));
        assertThat(result.get("created").asInt()).isPositive();
    }

    private String policy(int max, int warning, int critical, int window, Integer tardies) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("maxAbsences", max, "warningThreshold", warning,
                "criticalThreshold", critical, "justificationWindowBusinessDays", window));
        body.put("tardiesPerAbsence", tardies);
        return json.writeValueAsString(body);
    }
}
