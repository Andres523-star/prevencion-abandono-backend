package co.edu.retencion.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.notifications.application.NotificationService;
import co.edu.retencion.notifications.domain.Notification;
import co.edu.retencion.support.AttendanceWorld;
import co.edu.retencion.support.AttendanceWorld.Actor;
import co.edu.retencion.support.CampusIntegrationTest;
import co.edu.retencion.support.FakeClaude;

/**
 * Ciclo completo: umbral de asistencia → alerta asignada al consejero → el consejero revisa y edita
 * el plan → intervenciones → resolución → reanálisis (antes/después). Más deduplicación,
 * escalamiento y visibilidad de notas por rol.
 */
class AlertsInterventionsIT extends CampusIntegrationTest {

    @Autowired
    NotificationService notificationService;

    @Test
    void thresholdToAlertToPlanToInterventionToReanalysis() throws Exception {
        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of(ISW));
        Actor coordinator = staff(Set.of(Role.COORDINADOR), Set.of(ISW));
        onlyStaff(counselor, coordinator);
        CLAUDE.willReturnExplanation("claude-opus-5-5", FakeClaude.validExplanation("ALTO", "TUT-ACADEMICA"));
        CLAUDE.willRespondWhenBodyContains("Redacta un plan de apoyo", FakeClaude.ok(FakeClaude.textMessage(
                "claude-opus-5-5", FakeClaude.validPlan("Hola {{nombre}}, queremos ayudarte con {{materia}}."), "end_turn")));

        AttendanceWorld w = world(ISW);
        for (int i = 0; i < 3; i++) {
            markAbsence(w, i);
        }
        awaitListeners();

        // 1. Alerta de asistencia: una sola (aviso y crítico se deduplican), asignada al consejero del programa.
        relogin(counselor);
        JsonNode alerts = read(mvc.perform(get("/api/v1/students/" + w.studentId() + "/alerts")
                .header("Authorization", bearer(counselor.tokens()))).andExpect(status().isOk()));
        List<JsonNode> attendance = new ArrayList<>();
        alerts.forEach(a -> {
            if ("ASISTENCIA".equals(a.get("kind").asText())) {
                attendance.add(a);
            }
        });
        assertThat(attendance).hasSize(1);
        JsonNode alert = attendance.get(0);
        assertThat(alert.get("assignedTo").asText()).isEqualTo(counselor.userId().toString());
        assertThat(alert.get("level").asText()).isEqualTo("ALTO");
        assertThat(alert.get("attendanceLevel").asText()).isEqualTo("CRITICO");
        assertThat(alert.get("anonCode").asText()).startsWith("EST-");
        assertThat(alert.toString()).doesNotContain("Estudiante de Prueba");
        assertThat(notifications(counselor).findValuesAsText("type")).contains("ALERTA_ASIGNADA");

        // 2. El worker analiza y propone el plan (IA); el consejero lo edita, lo aprueba y lo convierte.
        awaitListeners();
        worker.drain(clock.instant().plus(Duration.ofHours(1)));
        relogin(counselor);
        JsonNode plan = read(mvc.perform(get("/api/v1/students/" + w.studentId() + "/support-plans")
                .header("Authorization", bearer(counselor.tokens()))).andExpect(status().isOk())).get(0);
        List<Map<String, Object>> edited = new ArrayList<>();
        for (JsonNode action : plan.get("actions")) {
            Map<String, Object> a = json.convertValue(action, Map.class);
            a.put("title", "Editado: " + action.get("title").asText());
            edited.add(a);
        }
        JsonNode interventions = read(postJson("/api/v1/support-plans/" + plan.get("id").asText() + "/convert",
                counselor.tokens(), Map.of("actions", edited, "comment", "Ajustado con el estudiante"))
                .andExpect(status().isCreated()));
        assertThat(interventions).hasSize(edited.size());
        assertThat(interventions.findValuesAsText("title")).allMatch(t -> t.startsWith("Editado: "));
        JsonNode first = interventions.get(0);
        assertThat(first.get("riskBefore").get("level").asText()).isNotBlank();
        assertThat(first.get("planId").asText()).isEqualTo(plan.get("id").asText());
        // El plan quedó aprobado y no se puede convertir dos veces.
        postJson("/api/v1/support-plans/" + plan.get("id").asText() + "/convert", counselor.tokens(),
                Map.of("actions", edited)).andExpect(status().isConflict());

        // 3. Resolver dispara un reanálisis y guarda el antes/después.
        String tutoring = null;
        for (JsonNode i : interventions) {
            if ("TUTORIA".equals(i.get("type").asText())) {
                tutoring = i.get("id").asText();
            }
        }
        assertThat(tutoring).isNotNull();
        postJson("/api/v1/interventions/" + tutoring + "/start", counselor.tokens(), Map.of())
                .andExpect(status().isOk());
        postJson("/api/v1/interventions/" + tutoring + "/resolve", counselor.tokens(), Map.of("result", " "))
                .andExpect(status().isBadRequest());
        JsonNode resolved = read(postJson("/api/v1/interventions/" + tutoring + "/resolve", counselor.tokens(),
                Map.of("result", "Asistió a tres tutorías y se puso al día")).andExpect(status().isOk()));
        assertThat(resolved.get("status").asText()).isEqualTo("RESUELTA");
        assertThat(resolved.get("riskAfter").get("assessmentId").asText()).isNotBlank();
        assertThat(resolved.get("scoreChange").isNull()).isFalse();
        Integer reanalysis = jdbc.queryForObject("SELECT count(*) FROM risk_assessments WHERE student_id = ? "
                + "AND trigger_type = 'INTERVENTION'", Integer.class, w.studentId());
        assertThat(reanalysis).isEqualTo(1);

        // 4. Escalamiento: sin atender en 48 h pasa a la coordinación del programa.
        clock.setNow(clock.instant().plus(Duration.ofHours(49)));
        Actor admin = staff(Set.of(Role.ADMIN), Set.of());
        int escalated = json.readTree(postJson("/api/v1/alerts/escalations/run", admin.tokens(), Map.of())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).asInt();
        assertThat(escalated).isPositive();
        awaitListeners();
        relogin(coordinator);
        JsonNode coordinatorAlerts = read(mvc.perform(get("/api/v1/alerts").param("mine", "true")
                .header("Authorization", bearer(coordinator.tokens()))).andExpect(status().isOk())).get("items");
        assertThat(coordinatorAlerts.findValuesAsText("id")).contains(alert.get("id").asText());
        assertThat(notifications(coordinator).findValuesAsText("type")).contains("ALERTA_ESCALADA");

        // 5. Cierre de la alerta: descartar exige nota.
        relogin(coordinator);
        postJson("/api/v1/alerts/" + alert.get("id").asText() + "/dismiss", coordinator.tokens(), Map.of("note", " "))
                .andExpect(status().isBadRequest());
        JsonNode attended = read(postJson("/api/v1/alerts/" + alert.get("id").asText() + "/attend", coordinator.tokens(),
                Map.of("note", "Plan en marcha")).andExpect(status().isOk()));
        assertThat(attended.get("status").asText()).isEqualTo("ATENDIDA");
    }

    @Test
    void psychologyNotesAreOnlyForPsychologists() throws Exception {
        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of(ISW));
        Actor psychologist = staff(Set.of(Role.PSICOLOGO), Set.of());
        Actor admin = staff(Set.of(Role.ADMIN), Set.of());
        UUID student = createStudent(admin.tokens(), ISW, null);
        String psych = read(postJson("/api/v1/students/" + student + "/interventions", counselor.tokens(), Map.of(
                "type", "PSICOLOGIA", "title", "Valoración por bienestar", "responsibleUserId", psychologist.userId()))
                .andExpect(status().isCreated())).get("id").asText();
        String tutoring = read(postJson("/api/v1/students/" + student + "/interventions", counselor.tokens(), Map.of(
                "type", "TUTORIA", "title", "Tutoría de cálculo")).andExpect(status().isCreated())).get("id").asText();

        // El consejero no escribe en una intervención de psicología; el psicólogo sí (nota restringida).
        postJson("/api/v1/interventions/" + psych + "/notes", counselor.tokens(), Map.of("text", "x"))
                .andExpect(status().isForbidden());
        JsonNode note = read(postJson("/api/v1/interventions/" + psych + "/notes", psychologist.tokens(),
                Map.of("text", "Sesión inicial, ansiedad por exámenes")).andExpect(status().isCreated()));
        assertThat(note.get("restricted").asBoolean()).isTrue();
        postJson("/api/v1/interventions/" + tutoring + "/notes", counselor.tokens(), Map.of("text", "Asistió"))
                .andExpect(status().isCreated());

        assertThat(read(mvc.perform(get("/api/v1/interventions/" + psych + "/notes")
                .header("Authorization", bearer(counselor.tokens()))).andExpect(status().isOk()))).isEmpty();
        assertThat(read(mvc.perform(get("/api/v1/interventions/" + psych + "/notes")
                .header("Authorization", bearer(psychologist.tokens()))).andExpect(status().isOk()))).hasSize(1);
        assertThat(read(mvc.perform(get("/api/v1/interventions/" + tutoring + "/notes")
                .header("Authorization", bearer(psychologist.tokens()))).andExpect(status().isOk()))).hasSize(1);
        assertThat(notifications(psychologist).findValuesAsText("type")).contains("INTERVENCION_ASIGNADA");
        // Un docente sin relación no ve intervenciones del estudiante.
        Actor teacher = staff(Set.of(Role.DOCENTE), Set.of());
        mvc.perform(get("/api/v1/students/" + student + "/interventions").header("Authorization", bearer(teacher.tokens())))
                .andExpect(status().isForbidden());
    }

    @Test
    void notificationsAreDeduplicatedAndCanBeMarkedRead() throws Exception {
        Actor teacher = staff(Set.of(Role.DOCENTE), Set.of());
        UUID student = createStudent(staff(Set.of(Role.ADMIN), Set.of()).tokens(), ISW, null);
        Map<String, Object> reason = Map.of("subject", "Anatomía", "average", 2.6, "trend", "BAJA", "absences", 3);
        assertThat(notificationService.notify(teacher.userId(), Notification.Type.RIESGO_ESTUDIANTE, student, "ALTO",
                reason, null)).isTrue();
        assertThat(notificationService.notify(teacher.userId(), Notification.Type.RIESGO_ESTUDIANTE, student, "ALTO",
                reason, null)).as("mismo estudiante, nivel y motivo").isFalse();
        assertThat(notificationService.notify(teacher.userId(), Notification.Type.RIESGO_ESTUDIANTE, student, "MEDIO",
                reason, null)).as("cambió el nivel").isTrue();
        relogin(teacher);
        JsonNode unread = read(mvc.perform(get("/api/v1/notifications/unread-count")
                .header("Authorization", bearer(teacher.tokens()))).andExpect(status().isOk()));
        assertThat(unread.get("unread").asLong()).isEqualTo(2);
        JsonNode items = notifications(teacher);
        // El docente no tiene alcance sobre este estudiante: no se resuelve el nombre.
        assertThat(items.get(0).get("studentName").isNull()).isTrue();
        assertThat(items.get(0).get("message").asText()).isEqualTo("Bajó su promedio a 2.6 en Anatomía y tiene 3 faltas");
        mvc.perform(post("/api/v1/notifications/" + items.get(0).get("id").asText() + "/read")
                .header("Authorization", bearer(teacher.tokens()))).andExpect(status().isNoContent());
        postJson("/api/v1/notifications/read-all", teacher.tokens(), Map.of()).andExpect(status().isOk());
        assertThat(read(mvc.perform(get("/api/v1/notifications/unread-count")
                .header("Authorization", bearer(teacher.tokens())))).get("unread").asLong()).isZero();
    }
}
