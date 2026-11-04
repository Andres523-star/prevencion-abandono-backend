package co.edu.retencion.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.common.time.TimeConfig;
import co.edu.retencion.support.AttendanceWorld.Actor;

/**
 * Utilidades de la Fase 3: periodo relativo a hoy (para tener sesiones pasadas), grupo con clase
 * diaria de 4 horas, actores que vuelven a iniciar sesión al mover el reloj y toma de asistencia.
 */
public abstract class RecordsIntegrationTest extends IntegrationTest {

    protected static final UUID IIN = UUID.fromString("0195f000-0000-7000-8000-000000000005");
    protected static final String STUDENT_DOMAIN = "estudiantes.universidad.edu.co";

    /** Usuario del personal con MFA (aal2). */
    protected Actor staff(Set<Role> roles, Set<UUID> programs) throws Exception {
        String subject = UUID.randomUUID().toString();
        String email = "u" + subject.substring(0, 8) + "@" + DOMAIN;
        Tokens first = loginWithSupabase(subject, email, "aal2");
        assignRoles(first.userId(), roles, programs);
        return new Actor(subject, email, "aal2", loginWithSupabase(subject, email, "aal2"));
    }

    /** Vuelve a iniciar sesión (los tokens se emiten con el reloj actual de la prueba). */
    protected void relogin(Actor... actors) throws Exception {
        for (Actor actor : actors) {
            actor.replace(loginWithSupabase(actor.subject(), actor.email(), actor.aal()));
        }
    }

    /** Mueve el reloj y vuelve a iniciar sesión con todos los actores del escenario. */
    protected void at(Instant instant, AttendanceWorld world, Actor... others) throws Exception {
        clock.setNow(instant);
        relogin(world.admin(), world.teacher(), world.student());
        relogin(others);
    }

    protected static LocalDate businessToday() {
        return LocalDate.now(TimeConfig.BUSINESS_ZONE);
    }

    /**
     * Escenario completo. El periodo empieza 5 semanas atrás, así hay sesiones pasadas sin importar
     * la fecha en que corra la prueba.
     */
    protected AttendanceWorld world(UUID programId) throws Exception {
        clock.reset();
        Actor admin = staff(Set.of(Role.ADMIN), Set.of());
        Actor teacher = staff(Set.of(Role.DOCENTE), Set.of());
        LocalDate today = businessToday();
        UUID termId = UUID.fromString(read(postJson("/api/v1/terms", admin.tokens(), Map.of(
                "code", uniqueCode("T-"), "name", "Periodo reciente", "startDate", today.minusDays(35).toString(),
                "endDate", today.plusDays(60).toString(), "status", "ACTIVE"))
                .andExpect(status().isCreated())).get("id").asText());
        UUID subjectId = createSubject(admin.tokens(), programId);

        Map<String, Object> group = new HashMap<>();
        group.put("subjectId", subjectId);
        group.put("termId", termId);
        group.put("code", uniqueCode("G"));
        group.put("teacherUserId", teacher.userId());
        group.put("capacity", 40);
        group.put("groupType", "REGULAR");
        List<Map<String, Object>> slots = new ArrayList<>();
        for (String day : List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY")) {
            // Sesión larga (4 horas): sigue contando como UNA falta.
            slots.add(Map.of("day", day, "start", "07:00", "end", "11:00", "room", "B-201"));
        }
        group.put("schedule", slots);
        UUID groupId = UUID.fromString(read(postJson("/api/v1/groups", admin.tokens(), group)
                .andExpect(status().isCreated())).get("id").asText());
        postJson("/api/v1/groups/" + groupId + "/sessions/generate", admin.tokens(), Map.of())
                .andExpect(status().isOk());

        String studentSubject = UUID.randomUUID().toString();
        String studentEmail = "e" + studentSubject.substring(0, 8) + "@" + STUDENT_DOMAIN;
        UUID studentId = createStudent(admin.tokens(), programId, studentEmail);
        UUID enrollmentId = UUID.fromString(read(postJson("/api/v1/students/" + studentId + "/enrollments",
                admin.tokens(), Map.of("groupId", groupId)).andExpect(status().isCreated())).get("id").asText());
        Actor student = new Actor(studentSubject, studentEmail, "aal1",
                loginWithSupabase(studentSubject, studentEmail, "aal1"));

        JsonNode sessions = read(mvc.perform(get("/api/v1/groups/" + groupId + "/sessions")
                .header("Authorization", bearer(admin.tokens()))
                .param("from", today.minusDays(35).toString()).param("to", today.minusDays(1).toString()))
                .andExpect(status().isOk()));
        List<JsonNode> past = new ArrayList<>();
        sessions.forEach(s -> {
            if ("PROGRAMADA".equals(s.get("status").asText())) {
                past.add(s);
            }
        });
        past.sort(Comparator.comparing(s -> s.get("date").asText()));
        return new AttendanceWorld(admin, teacher, student, programId, studentId, studentEmail, groupId, enrollmentId,
                "Materia de prueba", List.copyOf(past));
    }

    /** Toma la asistencia del estudiante del escenario en una sesión. */
    protected ResultActions mark(Tokens tokens, UUID sessionId, UUID enrollmentId, String attendance, String reason)
            throws Exception {
        var request = put("/api/v1/sessions/" + sessionId + "/attendance")
                .header("Authorization", bearer(tokens))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("entries",
                        List.of(Map.of("enrollmentId", enrollmentId, "status", attendance)))));
        if (reason != null) {
            request.header("X-Access-Reason", reason);
        }
        return mvc.perform(request);
    }

    /** Marca la sesión {@code index} como falta, a la hora del fin de la sesión + 1 h. */
    protected UUID markAbsence(AttendanceWorld world, int index) throws Exception {
        at(world.sessionEnd(index).plusSeconds(3600), world);
        JsonNode result = read(mark(world.teacher().tokens(), world.sessionId(index), world.enrollmentId(), "FALTA", null)
                .andExpect(status().isOk()));
        return UUID.fromString(result.get(0).get("recordId").asText());
    }

    protected JsonNode counter(AttendanceWorld world) throws Exception {
        JsonNode counters = read(mvc.perform(get("/api/v1/students/" + world.studentId() + "/absence-counters")
                .header("Authorization", bearer(world.admin().tokens()))).andExpect(status().isOk()));
        for (JsonNode c : counters) {
            if (c.get("enrollmentId").asText().equals(world.enrollmentId().toString())) {
                return c;
            }
        }
        throw new AssertionError("Sin contador para la inscripción");
    }
}
