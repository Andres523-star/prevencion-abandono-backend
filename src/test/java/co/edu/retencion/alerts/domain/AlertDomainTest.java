package co.edu.retencion.alerts.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.retencion.common.error.DomainException;

class AlertDomainTest {

    static final Instant NOW = Instant.parse("2026-11-05T15:00:00Z");

    static Alert.Signal signal(UUID student, Alert.Kind kind, UUID group, String level) {
        return new Alert.Signal(student, UUID.randomUUID(), 3, group, kind, level, null, new BigDecimal("40"), "RULES",
                null, "[]", "[]", false);
    }

    @Test
    void deduplicatesByStudentAndKindAndGroup() {
        UUID student = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        assertThat(Alert.dedupeKey(signal(student, Alert.Kind.RIESGO, group, "MEDIO"))).isEqualTo("RIESGO:" + student);
        assertThat(Alert.dedupeKey(signal(student, Alert.Kind.ASISTENCIA, group, "MEDIO")))
                .isEqualTo("ASISTENCIA:" + student + ":" + group);
        Alert alert = Alert.open(signal(student, Alert.Kind.RIESGO, null, "MEDIO"), NOW);
        assertThat(alert.getStatus()).isEqualTo(Alert.Status.NUEVA);
        assertThat(alert.refresh(signal(student, Alert.Kind.RIESGO, null, "MEDIO"), NOW)).isFalse();
        assertThat(alert.refresh(signal(student, Alert.Kind.RIESGO, null, "ALTO"), NOW)).isTrue();
        assertThat(alert.refresh(signal(student, Alert.Kind.RIESGO, null, "BAJO"), NOW)).isFalse();
    }

    @Test
    void lifecycleEscalationAndClosing() {
        Alert alert = Alert.open(signal(UUID.randomUUID(), Alert.Kind.RIESGO, null, "ALTO"), NOW);
        UUID counselor = UUID.randomUUID();
        alert.assign(counselor, Alert.AssigneeRole.CONSEJERO, NOW);
        alert.markSeen(NOW);
        assertThat(alert.getStatus()).isEqualTo(Alert.Status.VISTA);
        assertThat(alert.overdue(Duration.ofHours(48), NOW.plus(Duration.ofHours(47)))).isFalse();
        assertThat(alert.overdue(Duration.ofHours(48), NOW.plus(Duration.ofHours(48)))).isTrue();
        UUID coordinator = UUID.randomUUID();
        alert.escalate(coordinator, NOW.plus(Duration.ofHours(48)));
        assertThat(alert.getAssignedTo()).isEqualTo(coordinator);
        assertThat(alert.getAssignedRole()).isEqualTo(Alert.AssigneeRole.COORDINADOR);
        assertThat(alert.getEscalationLevel()).isEqualTo(1);
        assertThat(alert.overdue(Duration.ofHours(48), NOW.plus(Duration.ofHours(60)))).isFalse();
        assertThatThrownBy(() -> alert.close(false, coordinator, " ", NOW)).isInstanceOf(DomainException.class);
        alert.close(true, coordinator, "Contactado", NOW);
        assertThat(alert.getStatus()).isEqualTo(Alert.Status.ATENDIDA);
        assertThat(alert.overdue(Duration.ofHours(1), NOW.plus(Duration.ofDays(10)))).isFalse();
        assertThatThrownBy(() -> alert.close(true, coordinator, null, NOW)).isInstanceOf(DomainException.class);
        assertThat(Alert.rank(null)).isZero();
    }
}
