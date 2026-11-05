package co.edu.retencion.interventions.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.retencion.common.error.DomainException;

class InterventionDomainTest {

    static final Instant NOW = Instant.parse("2026-11-05T15:00:00Z");

    @Test
    void lifecycleMeasuresBeforeAndAfter() {
        Intervention.Data data = new Intervention.Data(UUID.randomUUID(), Intervention.Type.TUTORIA, " Tutoría ", " ",
                UUID.randomUUID(), "DOCENTE", null, null, null, null);
        Intervention i = Intervention.create(data, new Intervention.RiskMark(UUID.randomUUID(), "ALTO",
                new BigDecimal("70")), UUID.randomUUID(), NOW);
        assertThat(i.getTitle()).isEqualTo("Tutoría");
        assertThat(i.getDescription()).isNull();
        assertThat(i.notesAlwaysRestricted()).isFalse();
        i.start(NOW);
        assertThatThrownBy(() -> i.start(NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> i.resolve(" ", NOW)).isInstanceOf(DomainException.class);
        i.resolve("Asistió a 3 tutorías", NOW);
        assertThatThrownBy(() -> i.resolve("otra vez", NOW)).isInstanceOf(DomainException.class);
        i.measured(new Intervention.RiskMark(UUID.randomUUID(), "MEDIO", new BigDecimal("45")), NOW);
        assertThat(i.getRiskAfterLevel()).isEqualTo("MEDIO");
        assertThat(i.getStatus()).isEqualTo(Intervention.Status.RESUELTA);
        assertThatThrownBy(() -> Intervention.create(new Intervention.Data(UUID.randomUUID(), Intervention.Type.OTRO, "",
                null, null, null, null, null, null, null), null, UUID.randomUUID(), NOW))
                .isInstanceOf(DomainException.class);
        Intervention psych = Intervention.create(new Intervention.Data(UUID.randomUUID(), Intervention.Type.PSICOLOGIA,
                "Valoración", null, null, null, null, null, null, null), null, UUID.randomUUID(), NOW);
        assertThat(psych.notesAlwaysRestricted()).isTrue();
        assertThat(psych.getRiskBeforeAssessment()).isNull();
    }

    @Test
    void notesAndPlanMapping() {
        assertThatThrownBy(() -> InterventionNote.write(UUID.randomUUID(), UUID.randomUUID(), true, "", NOW))
                .isInstanceOf(DomainException.class);
        assertThat(InterventionNote.write(UUID.randomUUID(), UUID.randomUUID(), true, " nota ", NOW).getText())
                .isEqualTo("nota");
        assertThat(PlanActionMapping.typeOf("NIVELACION")).isEqualTo(Intervention.Type.TUTORIA);
        assertThat(PlanActionMapping.typeOf("REMISION_BIENESTAR")).isEqualTo(Intervention.Type.PSICOLOGIA);
        assertThat(PlanActionMapping.typeOf("AJUSTE_HORARIO")).isEqualTo(Intervention.Type.FLEXIBILIZACION);
        assertThat(PlanActionMapping.typeOf("APOYO_TRANSPORTE")).isEqualTo(Intervention.Type.AYUDA_ECONOMICA);
        assertThat(PlanActionMapping.typeOf("CONTACTO_DOCENTE")).isEqualTo(Intervention.Type.OTRO);
        assertThat(PlanActionMapping.typeOf(null)).isEqualTo(Intervention.Type.OTRO);
    }
}
