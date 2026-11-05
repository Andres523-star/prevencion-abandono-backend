package co.edu.retencion.retention.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.retencion.common.error.DomainException;

/** Transiciones válidas e inválidas de etapa de retention_case. */
class RetentionDomainTest {

    static final Instant NOW = Instant.parse("2026-11-05T15:00:00Z");

    static RetentionCase fresh() {
        return RetentionCase.open(UUID.randomUUID(), UUID.randomUUID(), null, RetentionCase.Origin.CONSEJERO,
                UUID.randomUUID(), UUID.randomUUID(), NOW);
    }

    @Test
    void stagesAdvanceOneAtATimeWithTheirRequirements() {
        RetentionCase c = fresh();
        assertThat(c.getStage()).isEqualTo(RetentionCase.Stage.DETECTADO);
        assertThatThrownBy(() -> c.advanceTo(RetentionCase.Stage.ENTREVISTA, true, true, NOW))
                .isInstanceOf(DomainException.class).hasMessageContaining("Transición inválida");
        assertThatThrownBy(() -> c.advanceTo(RetentionCase.Stage.CONTACTADO, false, true, NOW))
                .hasMessageContaining("contacto");
        c.advanceTo(RetentionCase.Stage.CONTACTADO, true, false, NOW);
        assertThat(c.getContactedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> c.advanceTo(RetentionCase.Stage.DETECTADO, true, true, NOW))
                .hasMessageContaining("Transición inválida");
        c.advanceTo(RetentionCase.Stage.ENTREVISTA, true, false, NOW);
        c.advanceTo(RetentionCase.Stage.PLAN_DE_APOYO, true, false, NOW);
        assertThatThrownBy(() -> c.advanceTo(RetentionCase.Stage.SEGUIMIENTO, true, false, NOW))
                .hasMessageContaining("tarea");
        assertThatThrownBy(() -> c.close(RetentionCase.Outcome.RETENIDO, null, null, NOW))
                .hasMessageContaining("RETENIDO");
        c.advanceTo(RetentionCase.Stage.SEGUIMIENTO, true, true, NOW);
        assertThatThrownBy(() -> c.advanceTo(RetentionCase.Stage.CERRADO, true, true, NOW))
                .hasMessageContaining("cierre");
        c.close(RetentionCase.Outcome.RETENIDO, "IGNORADA", null, NOW);
        assertThat(c.isOpen()).isFalse();
        assertThat(c.getExitCauseCode()).isNull();
        assertThatThrownBy(() -> c.close(RetentionCase.Outcome.RETIRADO, "X", "{}", NOW)).hasMessageContaining("cerrado");
        assertThatThrownBy(() -> c.advanceTo(RetentionCase.Stage.SEGUIMIENTO, true, true, NOW))
                .hasMessageContaining("cerrado");
    }

    @Test
    void withdrawalNeedsCauseAndExitSurveyFromAnyOpenStage() {
        RetentionCase c = fresh();
        assertThatThrownBy(() -> c.close(RetentionCase.Outcome.RETIRADO, null, "{\"q\":1}", NOW))
                .hasMessageContaining("causa");
        assertThatThrownBy(() -> c.close(RetentionCase.Outcome.RETIRADO, "NECESIDAD_DE_TRABAJAR", null, NOW))
                .hasMessageContaining("encuesta");
        c.close(RetentionCase.Outcome.RETIRADO, "NECESIDAD_DE_TRABAJAR", "{\"q\":1}", NOW);
        assertThat(c.getOutcome()).isEqualTo(RetentionCase.Outcome.RETIRADO);
        RetentionCase transfer = fresh();
        assertThatThrownBy(() -> transfer.close(RetentionCase.Outcome.TRANSFERIDO, " ", null, NOW))
                .isInstanceOf(DomainException.class);
        RetentionCase postponed = fresh();
        postponed.close(RetentionCase.Outcome.APLAZADO, null, null, NOW);
        assertThat(postponed.getStage()).isEqualTo(RetentionCase.Stage.CERRADO);
        postponed.assign(UUID.randomUUID(), NOW);
    }

    @Test
    void auxiliaryRecords() {
        UUID caseId = UUID.randomUUID();
        assertThat(ContactAttempt.of(caseId, ContactAttempt.Channel.TELEFONO, ContactAttempt.Result.NO_CONTESTA, " ",
                UUID.randomUUID(), NOW).getNotes()).isNull();
        assertThatThrownBy(() -> InterviewNote.write(caseId, UUID.randomUUID(), "", NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> CaseTask.of(caseId, "x", null, UUID.randomUUID(), NOW)).isInstanceOf(DomainException.class);
        CaseTask task = CaseTask.of(caseId, " Llamar ", LocalDate.of(2026, 11, 10), UUID.randomUUID(), NOW);
        task.done(NOW);
        task.done(NOW.plusSeconds(5));
        assertThat(task.getDoneAt()).isEqualTo(NOW);
        FollowUp f = FollowUp.schedule(caseId, FollowUp.Kind.SEGUIMIENTO, 7, LocalDate.of(2026, 11, 12));
        f.reminded(NOW);
        f.done(NOW);
        f.cancel();
        assertThat(f.getStatus()).isEqualTo(FollowUp.Status.HECHO);
        ReentryCampaign campaign = ReentryCampaign.draft("[]", 0, null, NOW);
        campaign.review(true, UUID.randomUUID(), NOW);
        assertThatThrownBy(() -> campaign.review(false, UUID.randomUUID(), NOW)).isInstanceOf(DomainException.class);
    }
}
