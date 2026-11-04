package co.edu.retencion.wellbeing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.retencion.common.error.DomainException;

class WellbeingDomainTest {

    static final Instant NOW = Instant.parse("2026-10-05T17:00:00Z");

    @Test
    void referralLifecycle() {
        WellbeingReferral referral = WellbeingReferral.open(UUID.randomUUID(), null, WellbeingReferral.Service.PSICOLOGIA,
                " Ansiedad ", UUID.randomUUID(), NOW);
        assertThat(referral.getReason()).isEqualTo("Ansiedad");
        assertThat(referral.toString()).doesNotContain("Ansiedad");
        referral.moveTo(WellbeingReferral.Status.ATENDIDA, NOW);
        assertThatThrownBy(() -> referral.moveTo(WellbeingReferral.Status.ABIERTA, NOW))
                .isInstanceOf(DomainException.class);
        referral.moveTo(WellbeingReferral.Status.CERRADA, NOW);
        assertThat(referral.getClosedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> referral.moveTo(WellbeingReferral.Status.CERRADA, NOW))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> WellbeingReferral.open(UUID.randomUUID(), null, WellbeingReferral.Service.EXTERNA, "",
                UUID.randomUUID(), NOW)).isInstanceOf(DomainException.class);
    }

    @Test
    void assessmentsValidateTextAndHideItInToString() {
        WellbeingAssessment assessment = WellbeingAssessment.record(UUID.randomUUID(),
                WellbeingAssessment.Type.PSICOLOGICA, "Estrés", " ", UUID.randomUUID(), LocalDate.of(2026, 10, 1), NOW);
        assertThat(assessment.getFindings()).isNull();
        assertThat(assessment.toString()).doesNotContain("Estrés");
        assertThatThrownBy(() -> WellbeingAssessment.record(UUID.randomUUID(), WellbeingAssessment.Type.FISICA, null,
                null, UUID.randomUUID(), LocalDate.of(2026, 10, 1), NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> WellbeingAssessment.record(UUID.randomUUID(), WellbeingAssessment.Type.FISICA, "ok",
                "x".repeat(8001), UUID.randomUUID(), LocalDate.of(2026, 10, 1), NOW))
                .isInstanceOf(DomainException.class);
    }
}
