package co.edu.retencion.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class NotificationDomainTest {

    @Test
    void academicReasonReadsLikeTheProductExample() {
        assertThat(ReasonText.of(Notification.Type.RIESGO_ESTUDIANTE, Map.of("subject", "Anatomía", "average", 2.6,
                "trend", "BAJA", "absences", 3))).isEqualTo("Bajó su promedio a 2.6 en Anatomía y tiene 3 faltas");
        assertThat(ReasonText.of(Notification.Type.RIESGO_ESTUDIANTE, Map.of("subject", "Química", "average", "2.4",
                "trend", "ESTABLE", "absences", 1, "maxAbsences", 4)))
                .isEqualTo("Tiene promedio de 2.4 en Química y tiene 1 falta (límite 4)");
        assertThat(ReasonText.of(Notification.Type.RIESGO_ESTUDIANTE, Map.of("subject", "Física", "absences", 2,
                "maxAbsences", 4))).isEqualTo("Tiene 2 faltas en Física (límite 4)");
        assertThat(ReasonText.of(Notification.Type.RIESGO_ESTUDIANTE, Map.of())).contains("riesgo académico");
    }

    @Test
    void everyTypeHasAText() {
        for (Notification.Type type : Notification.Type.values()) {
            assertThat(ReasonText.of(type, Map.of("level", "ALTO", "kind", "ASISTENCIA", "subject", "Física",
                    "approved", true, "type", "TUTORIA", "offsetDays", 7))).isNotBlank();
        }
        assertThat(ReasonText.of(Notification.Type.JUSTIFICACION_DECIDIDA, Map.of("approved", false))).contains("rechazada");
        assertThat(ReasonText.of(Notification.Type.ALERTA_ESCALADA, Map.of("level", "alto", "kind", "PERDIDA")))
                .contains("ALTO").contains("pérdida");
        Notification n = Notification.of(UUID.randomUUID(), Notification.Type.NUEVO_MENSAJE, null, null, "{}", "h", null,
                Instant.now(), Instant.now());
        n.markRead(Instant.EPOCH);
        n.markRead(Instant.now());
        assertThat(n.getReadAt()).isEqualTo(Instant.EPOCH);
    }
}
