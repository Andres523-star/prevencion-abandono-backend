package co.edu.retencion.records.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.retencion.academic.EffectiveAttendancePolicy;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.records.AttendanceLevel;
import co.edu.retencion.records.AttendanceStatus;
import co.edu.retencion.records.JustificationState;
import co.edu.retencion.records.domain.AbsenceTally.Mark;

/** Reglas puras de asistencia y notas. */
class RecordsDomainTest {

    static final EffectiveAttendancePolicy DEFAULT = new EffectiveAttendancePolicy(4, 2, 3, 3, null,
            EffectiveAttendancePolicy.PolicySource.PROGRAM);
    static final Instant NOW = Instant.parse("2026-10-05T17:00:00Z");

    @Test
    void tallyCountsUnjustifiedAndPendingButNotApproved() {
        AbsenceTally tally = AbsenceTally.of(List.of(
                new Mark(AttendanceStatus.FALTA, JustificationState.NINGUNA),
                new Mark(AttendanceStatus.FALTA, JustificationState.PENDIENTE),
                new Mark(AttendanceStatus.FALTA, JustificationState.APROBADA),
                new Mark(AttendanceStatus.FALTA, JustificationState.RECHAZADA),
                new Mark(AttendanceStatus.FALTA, JustificationState.VENCIDA),
                new Mark(AttendanceStatus.PRESENTE, JustificationState.NINGUNA),
                new Mark(AttendanceStatus.TARDANZA, JustificationState.NINGUNA)), DEFAULT);
        assertThat(tally.unjustified()).isEqualTo(3);
        assertThat(tally.pending()).isEqualTo(1);
        assertThat(tally.justified()).isEqualTo(1);
        assertThat(tally.tardies()).isEqualTo(1);
        assertThat(tally.countedAbsences()).isEqualTo(4);
        assertThat(tally.level()).isEqualTo(AttendanceLevel.PERDIDA);
    }

    @Test
    void tardiesCountOnlyWhenThePolicySaysSo() {
        List<Mark> tardies = List.of(new Mark(AttendanceStatus.TARDANZA, JustificationState.NINGUNA),
                new Mark(AttendanceStatus.TARDANZA, JustificationState.NINGUNA),
                new Mark(AttendanceStatus.TARDANZA, JustificationState.NINGUNA));
        assertThat(AbsenceTally.of(tardies, DEFAULT).countedAbsences()).isZero();
        EffectiveAttendancePolicy withTardies = new EffectiveAttendancePolicy(4, 2, 3, 3, 3,
                EffectiveAttendancePolicy.PolicySource.SUBJECT);
        assertThat(AbsenceTally.of(tardies, withTardies).countedAbsences()).isEqualTo(1);
    }

    @Test
    void levelsFollowThresholds() {
        assertThat(AttendanceLevel.of(0, 2, 3, 4)).isEqualTo(AttendanceLevel.NINGUNO);
        assertThat(AttendanceLevel.of(1, 2, 3, 4)).isEqualTo(AttendanceLevel.NINGUNO);
        assertThat(AttendanceLevel.of(2, 2, 3, 4)).isEqualTo(AttendanceLevel.AVISO);
        assertThat(AttendanceLevel.of(3, 2, 3, 4)).isEqualTo(AttendanceLevel.CRITICO);
        assertThat(AttendanceLevel.of(5, 2, 3, 4)).isEqualTo(AttendanceLevel.PERDIDA);
        assertThat(AttendanceLevel.CRITICO.isAbove(AttendanceLevel.AVISO)).isTrue();
        assertThat(AttendanceLevel.AVISO.isAbove(AttendanceLevel.AVISO)).isFalse();
    }

    @Test
    void counterReportsThePreviousLevelSoOnlyRisesPublishEvents() {
        AbsenceCounter counter = AbsenceCounter.empty(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 4, NOW);
        AbsenceTally two = AbsenceTally.of(List.of(new Mark(AttendanceStatus.FALTA, JustificationState.NINGUNA),
                new Mark(AttendanceStatus.FALTA, JustificationState.NINGUNA)), DEFAULT);
        assertThat(counter.apply(two, 4, NOW)).isEqualTo(AttendanceLevel.NINGUNO);
        assertThat(counter.apply(two, 4, NOW)).isEqualTo(AttendanceLevel.AVISO);
        assertThat(counter.info().countedAbsences()).isEqualTo(2);
        assertThat(counter.info().level()).isEqualTo(AttendanceLevel.AVISO);
    }

    @Test
    void businessDaysSkipWeekendsAndHolidays() {
        LocalDate friday = LocalDate.of(2026, 10, 9);
        Set<LocalDate> holidays = Set.of(LocalDate.of(2026, 10, 12));
        assertThat(BusinessDays.add(friday, 1, holidays)).isEqualTo(LocalDate.of(2026, 10, 13));
        assertThat(BusinessDays.add(friday, 3, Set.of())).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(BusinessDays.isBusinessDay(LocalDate.of(2026, 10, 10), Set.of())).isFalse();
        assertThat(BusinessDays.isBusinessDay(LocalDate.of(2026, 10, 12), holidays)).isFalse();
    }

    @Test
    void attendanceRecordJustificationLifecycle() {
        UUID actor = UUID.randomUUID();
        AttendanceRecord record = AttendanceRecord.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), NOW);
        LocalDate deadline = LocalDate.of(2026, 10, 8);
        assertThat(record.mark(AttendanceStatus.FALTA, deadline, actor, NOW)).isTrue();
        assertThat(record.mark(AttendanceStatus.FALTA, deadline, actor, NOW)).isFalse();

        assertThatThrownBy(() -> record.startJustification(LocalDate.of(2026, 10, 9), NOW))
                .isInstanceOf(DomainException.class);
        record.startJustification(LocalDate.of(2026, 10, 7), NOW);
        assertThatThrownBy(() -> record.startJustification(LocalDate.of(2026, 10, 7), NOW))
                .isInstanceOf(DomainException.class);
        record.resolveJustification(true, NOW);
        assertThat(record.getJustification()).isEqualTo(JustificationState.APROBADA);
        // Una falta ya revisada no se puede cambiar a presente sin proceso de corrección.
        assertThatThrownBy(() -> record.mark(AttendanceStatus.PRESENTE, deadline, actor, NOW))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void overdueAbsencesExpire() {
        AttendanceRecord record = AttendanceRecord.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), NOW);
        record.mark(AttendanceStatus.FALTA, LocalDate.of(2026, 10, 8), UUID.randomUUID(), NOW);
        assertThat(record.expireIfOverdue(LocalDate.of(2026, 10, 8), NOW)).isFalse();
        assertThat(record.expireIfOverdue(LocalDate.of(2026, 10, 9), NOW)).isTrue();
        assertThat(record.getJustification()).isEqualTo(JustificationState.VENCIDA);
    }

    @Test
    void gradeIndicatorsDetectLowAverageAndDownwardTrend() {
        UUID anatomy = UUID.randomUUID();
        UUID law = UUID.randomUUID();
        GradeIndicators.Summary summary = GradeIndicators.compute(List.of(
                point(anatomy, "3.8", 1), point(anatomy, "3.0", 2), point(anatomy, "2.4", 3),
                point(law, "4.0", 1), point(law, "4.5", 2)));
        GradeIndicators.SubjectIndicator a = summary.subjects().stream()
                .filter(s -> s.groupId().equals(anatomy)).findFirst().orElseThrow();
        assertThat(a.average()).isEqualByComparingTo("3.07");
        assertThat(a.trend()).isEqualTo(GradeIndicators.Trend.BAJA);
        assertThat(a.lastGrade()).isEqualByComparingTo("2.4");
        GradeIndicators.SubjectIndicator l = summary.subjects().stream()
                .filter(s -> s.groupId().equals(law)).findFirst().orElseThrow();
        assertThat(l.trend()).isEqualTo(GradeIndicators.Trend.SUBE);
        assertThat(summary.subjectsBelowPassing()).isZero();
        assertThat(GradeIndicators.compute(List.of(point(law, "2.0", 1))).subjects().getFirst().trend())
                .isEqualTo(GradeIndicators.Trend.INSUFICIENTE);
    }

    @Test
    void gradesAreValidatedAndNormalized() {
        Grade grade = Grade.record(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("3"), LocalDate.of(2026, 10, 1), "Parcial", UUID.randomUUID(), NOW);
        assertThat(grade.getValue()).isEqualTo(new BigDecimal("3.0"));
        assertThatThrownBy(() -> Grade.record(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), new BigDecimal("2.95"), LocalDate.of(2026, 10, 1), null, UUID.randomUUID(), NOW))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Grade.record(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), new BigDecimal("5.1"), LocalDate.of(2026, 10, 1), null, UUID.randomUUID(), NOW))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void observationsRequireTextAndOnlyTheAuthorDeletes() {
        UUID author = UUID.randomUUID();
        Observation observation = Observation.write(UUID.randomUUID(), null, author, Observation.Type.CONDUCTA,
                " Llega tarde ", NOW);
        assertThat(observation.getText()).isEqualTo("Llega tarde");
        assertThatThrownBy(() -> observation.delete(UUID.randomUUID(), NOW)).isInstanceOf(RuntimeException.class);
        observation.delete(author, NOW);
        assertThat(observation.getDeletedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> Observation.write(UUID.randomUUID(), null, author, Observation.Type.ACADEMICA, " ", NOW))
                .isInstanceOf(DomainException.class);
    }

    private static GradeIndicators.Point point(UUID group, String value, int day) {
        return new GradeIndicators.Point(group, new BigDecimal(value), LocalDate.of(2026, 9, day));
    }
}
