package co.edu.retencion.justifications.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.retencion.common.error.DomainException;

class JustificationAppealTest {

    private static final Instant NOW = Instant.parse("2026-11-09T15:00:00Z");

    private static AbsenceJustification rejected(UUID teacher) {
        AbsenceJustification j = AbsenceJustification.submit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), AbsenceJustification.Category.SALUD, "Cita médica",
                LocalDate.of(2026, 11, 12), teacher, NOW);
        j.decide(false, teacher, "Falta el soporte", NOW);
        return j;
    }

    @Test
    void aRejectedJustificationCanBeAppealedOnceWithinTheWindow() {
        UUID teacher = UUID.randomUUID();
        AbsenceJustification j = rejected(teacher);
        assertThat(j.awaitsTeacher()).isFalse();
        assertThatThrownBy(() -> j.appeal(" ", 5, NOW)).isInstanceOf(DomainException.class);
        j.appeal("Adjunto la incapacidad", 5, NOW.plus(Duration.ofDays(2)));
        assertThat(j.getStatus()).isEqualTo(AbsenceJustification.Status.APELADA);
        assertThat(j.awaitsTeacher()).isTrue();
        assertThat(j.getAppealText()).isEqualTo("Adjunto la incapacidad");
        j.reassign(teacher, NOW);
        j.decide(true, teacher, null, NOW.plus(Duration.ofDays(3)));
        assertThat(j.getStatus()).isEqualTo(AbsenceJustification.Status.APROBADA);
        assertThatThrownBy(() -> j.appeal("otra vez", 5, NOW)).isInstanceOf(DomainException.class);
    }

    @Test
    void appealsAreLimitedInTimeAndToRejectedJustifications() {
        AbsenceJustification late = rejected(UUID.randomUUID());
        assertThatThrownBy(() -> late.appeal("Tarde", 5, NOW.plus(Duration.ofDays(6)))).isInstanceOf(DomainException.class);

        UUID teacher = UUID.randomUUID();
        AbsenceJustification twice = rejected(teacher);
        twice.appeal("Primera", 5, NOW);
        twice.decide(false, teacher, "Sigue sin soporte", NOW);
        assertThat(twice.getStatus()).isEqualTo(AbsenceJustification.Status.RECHAZADA);
        assertThatThrownBy(() -> twice.appeal("Segunda", 5, NOW)).isInstanceOf(DomainException.class);

        AbsenceJustification pending = AbsenceJustification.submit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), AbsenceJustification.Category.OTRO, "x", LocalDate.of(2026, 11, 12),
                teacher, NOW);
        assertThatThrownBy(() -> pending.appeal("x", 5, NOW)).isInstanceOf(DomainException.class);
    }
}
