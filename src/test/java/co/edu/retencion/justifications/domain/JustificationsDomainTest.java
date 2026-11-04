package co.edu.retencion.justifications.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.retencion.common.error.DomainException;

class JustificationsDomainTest {

    static final Instant NOW = Instant.parse("2026-10-05T17:00:00Z");

    @Test
    void onlyTheAssignedTeacherDecidesAndRejectionNeedsAComment() {
        UUID teacher = UUID.randomUUID();
        AbsenceJustification j = submit(teacher);
        assertThat(j.getStatus()).isEqualTo(AbsenceJustification.Status.PENDIENTE);
        assertThat(j.isAssignedTo(teacher)).isTrue();
        assertThat(j.isAssignedTo(UUID.randomUUID())).isFalse();

        assertThatThrownBy(() -> j.decide(false, teacher, " ", NOW)).isInstanceOf(DomainException.class);
        j.decide(false, teacher, "Sin soporte", NOW);
        assertThat(j.getStatus()).isEqualTo(AbsenceJustification.Status.RECHAZADA);
        assertThat(j.getReviewComment()).isEqualTo("Sin soporte");
        assertThatThrownBy(() -> j.decide(true, teacher, null, NOW)).isInstanceOf(DomainException.class);
    }

    @Test
    void pendingJustificationsCanBeReassigned() {
        AbsenceJustification j = submit(UUID.randomUUID());
        UUID next = UUID.randomUUID();
        j.reassign(next, NOW);
        assertThat(j.isAssignedTo(next)).isTrue();
        j.decide(true, next, null, NOW);
        assertThat(j.getStatus()).isEqualTo(AbsenceJustification.Status.APROBADA);
        assertThat(j.toString()).doesNotContain("Cita");
    }

    @Test
    void descriptionIsRequired() {
        assertThatThrownBy(() -> AbsenceJustification.submit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), AbsenceJustification.Category.OTRO, "", LocalDate.now(),
                UUID.randomUUID(), NOW)).isInstanceOf(DomainException.class);
    }

    @Test
    void evidenceUsesARandomKeyAndIsAvailableOnlyWhenClean() {
        JustificationEvidence evidence = JustificationEvidence.uploaded(UUID.randomUUID(), "pdf", "application/pdf",
                100, "ab".repeat(32), UUID.randomUUID(), NOW);
        JustificationEvidence other = JustificationEvidence.uploaded(UUID.randomUUID(), "pdf", "application/pdf",
                100, "ab".repeat(32), UUID.randomUUID(), NOW);
        assertThat(evidence.getObjectKey()).startsWith("evidencias/").endsWith("pdf")
                .isNotEqualTo(other.getObjectKey());
        assertThat(evidence.isAvailable()).isFalse();
        evidence.scanned(JustificationEvidence.ScanStatus.INFECTADO, NOW);
        assertThat(evidence.isAvailable()).isFalse();
        other.scanned(JustificationEvidence.ScanStatus.LIMPIO, NOW);
        assertThat(other.isAvailable()).isTrue();
        assertThat(other.downloadName()).endsWith(".pdf");
    }

    private static AbsenceJustification submit(UUID teacher) {
        return AbsenceJustification.submit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), AbsenceJustification.Category.SALUD, "Cita médica", LocalDate.of(2026, 10, 8),
                teacher, NOW);
    }
}
