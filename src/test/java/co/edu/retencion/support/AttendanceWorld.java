package co.edu.retencion.support;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import co.edu.retencion.common.time.TimeConfig;
import co.edu.retencion.support.IntegrationTest.Tokens;

/**
 * Escenario de asistencia para las pruebas de la Fase 3: un grupo con clase diaria de 4 horas,
 * su docente, un estudiante inscrito y las sesiones ya pasadas del periodo.
 *
 * @param subjectName nombre de la materia (aparece en el correo)
 */
public record AttendanceWorld(Actor admin, Actor teacher, Actor student, UUID programId, UUID studentId,
                              String studentEmail, UUID groupId, UUID enrollmentId, String subjectName,
                              List<JsonNode> pastSessions) {

    /** Usuario de prueba que puede volver a iniciar sesión (p. ej. tras mover el reloj). */
    public static final class Actor {

        private final String subject;
        private final String email;
        private final String aal;
        private Tokens tokens;

        public Actor(String subject, String email, String aal, Tokens tokens) {
            this.subject = subject;
            this.email = email;
            this.aal = aal;
            this.tokens = tokens;
        }

        public Tokens tokens() {
            return tokens;
        }

        public UUID userId() {
            return tokens.userId();
        }

        public String subject() {
            return subject;
        }

        public String email() {
            return email;
        }

        public String aal() {
            return aal;
        }

        void replace(Tokens tokens) {
            this.tokens = tokens;
        }
    }

    public JsonNode session(int index) {
        return pastSessions.get(index);
    }

    public UUID sessionId(int index) {
        return UUID.fromString(session(index).get("id").asText());
    }

    public LocalDate sessionDate(int index) {
        return LocalDate.parse(session(index).get("date").asText());
    }

    /** Fin de la sesión en hora de Bogotá. */
    public Instant sessionEnd(int index) {
        return ZonedDateTime.of(sessionDate(index), LocalTime.parse(session(index).get("end").asText()),
                TimeConfig.BUSINESS_ZONE).toInstant();
    }
}
