package co.edu.retencion.privacy.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.retencion.common.error.DomainException;

class PrivacyDomainTest {

    private static final Instant NOW = Instant.parse("2026-11-09T15:00:00Z");
    private static final LocalDate DUE = LocalDate.of(2026, 11, 23);

    private static DataSubjectRequest request(DataSubjectRequest.Type type, String detail) {
        return DataSubjectRequest.submit(UUID.randomUUID(), UUID.randomUUID(), type, detail, DUE, NOW);
    }

    @Test
    void businessDaysSkipWeekendsAndHolidays() {
        LocalDate friday = LocalDate.of(2026, 11, 6);
        assertThat(BusinessDays.add(friday, 1, Set.of())).isEqualTo(LocalDate.of(2026, 11, 9));
        // 16 de noviembre de 2026 es festivo (Independencia de Cartagena, trasladado).
        assertThat(BusinessDays.add(friday, 6, Set.of(LocalDate.of(2026, 11, 16)))).isEqualTo(LocalDate.of(2026, 11, 17));
        assertThat(BusinessDays.add(friday, 0, Set.of())).isEqualTo(friday);
    }

    @Test
    void accessRequestIsApprovedExecutedAndDeliversACopy() {
        DataSubjectRequest r = request(DataSubjectRequest.Type.ACCESO, null);
        assertThat(r.getStatus()).isEqualTo(DataSubjectRequest.Status.RADICADA);
        assertThat(DataSubjectRequest.Type.ACCESO.isClaim()).isFalse();
        UUID admin = UUID.randomUUID();
        r.approve(admin, " ", NOW);
        assertThat(r.getDecisionComment()).isNull();
        r.attachExport("privacidad/x-json", NOW.plusSeconds(3600), NOW);
        r.execute(admin, null, NOW);
        assertThat(r.getStatus()).isEqualTo(DataSubjectRequest.Status.EJECUTADA);
        assertThat(r.exportAvailable(NOW)).isTrue();
        assertThat(r.exportAvailable(NOW.plusSeconds(7200))).isFalse();
        r.expireExport(NOW);
        assertThat(r.exportAvailable(NOW)).isFalse();
        assertThat(r.toString()).doesNotContain("privacidad/");
    }

    @Test
    void suppressionNeedsTwoPeople() {
        DataSubjectRequest r = request(DataSubjectRequest.Type.SUPRESION, "Ya no estudio aquí");
        assertThat(DataSubjectRequest.Type.SUPRESION.isClaim()).isTrue();
        UUID approver = UUID.randomUUID();
        r.approve(approver, "Procede", NOW);
        assertThatThrownBy(() -> r.execute(approver, null, NOW)).isInstanceOf(DomainException.class);
        r.execute(UUID.randomUUID(), "Ejecutada", NOW);
        assertThat(r.getStatus()).isEqualTo(DataSubjectRequest.Status.EJECUTADA);
        assertThat(r.getDecisionComment()).isEqualTo("Procede\nEjecutada");
        assertThatThrownBy(() -> r.attachExport("k", NOW, NOW)).isInstanceOf(DomainException.class);
    }

    @Test
    void rectificationNeedsDetailAndANoteAndRejectionNeedsAReason() {
        assertThatThrownBy(() -> request(DataSubjectRequest.Type.RECTIFICACION, " ")).isInstanceOf(DomainException.class);
        DataSubjectRequest r = request(DataSubjectRequest.Type.RECTIFICACION, "Mi teléfono cambió");
        r.approve(UUID.randomUUID(), null, NOW);
        assertThatThrownBy(() -> r.execute(UUID.randomUUID(), null, NOW)).isInstanceOf(DomainException.class);
        r.execute(UUID.randomUUID(), "Teléfono actualizado", NOW);
        assertThat(r.getDecisionComment()).isEqualTo("Teléfono actualizado");

        DataSubjectRequest other = request(DataSubjectRequest.Type.ACCESO, null);
        assertThatThrownBy(() -> other.reject(UUID.randomUUID(), " ", NOW)).isInstanceOf(DomainException.class);
        other.reject(UUID.randomUUID(), "No es titular", NOW);
        assertThat(other.getStatus()).isEqualTo(DataSubjectRequest.Status.RECHAZADA);
        assertThatThrownBy(() -> other.approve(UUID.randomUUID(), null, NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> other.execute(UUID.randomUUID(), "x", NOW)).isInstanceOf(DomainException.class);
    }

    @Test
    void openRequestsBecomeOverdueAfterTheLegalDeadline() {
        DataSubjectRequest r = request(DataSubjectRequest.Type.ACCESO, null);
        assertThat(r.isOverdue(DUE)).isFalse();
        assertThat(r.isOverdue(DUE.plusDays(1))).isTrue();
        r.reject(UUID.randomUUID(), "motivo", NOW);
        assertThat(r.isOverdue(DUE.plusDays(1))).isFalse();
    }
}
