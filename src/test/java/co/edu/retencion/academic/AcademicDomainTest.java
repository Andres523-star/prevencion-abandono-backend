package co.edu.retencion.academic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.retencion.academic.EffectiveAttendancePolicy.PolicySource;
import co.edu.retencion.academic.domain.AttendancePolicy;
import co.edu.retencion.academic.domain.ClassSession;
import co.edu.retencion.academic.domain.ClassSuspension;
import co.edu.retencion.academic.domain.ColombianHolidays;
import co.edu.retencion.academic.domain.CourseGroup;
import co.edu.retencion.academic.domain.PolicyValues;
import co.edu.retencion.academic.domain.Program;
import co.edu.retencion.academic.domain.SessionPlanner;
import co.edu.retencion.academic.domain.SessionPlanner.WeeklySlot;
import co.edu.retencion.academic.domain.Subject;
import co.edu.retencion.academic.domain.Term;
import co.edu.retencion.common.error.DomainException;

/** Reglas del dominio académico, sin Spring. */
class AcademicDomainTest {

    private static final Instant NOW = Instant.parse("2026-11-03T15:00:00Z");

    @Test
    void colombianHolidays2026FollowEmilianiLaw() {
        Map<LocalDate, String> holidays = ColombianHolidays.forYear(2026);
        assertThat(holidays).containsKeys(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 12), LocalDate.of(2026, 3, 23),
                LocalDate.of(2026, 4, 2), LocalDate.of(2026, 4, 3), LocalDate.of(2026, 5, 1),
                LocalDate.of(2026, 5, 18), LocalDate.of(2026, 6, 8), LocalDate.of(2026, 6, 15),
                LocalDate.of(2026, 6, 29), LocalDate.of(2026, 7, 20), LocalDate.of(2026, 8, 7),
                LocalDate.of(2026, 8, 17), LocalDate.of(2026, 10, 12), LocalDate.of(2026, 11, 2),
                LocalDate.of(2026, 11, 16), LocalDate.of(2026, 12, 8), LocalDate.of(2026, 12, 25));
        assertThat(holidays).hasSize(18);
        // Pascua 2025: 20 de abril → Viernes Santo 18 de abril.
        assertThat(ColombianHolidays.forYear(2025)).containsKey(LocalDate.of(2025, 4, 18));
    }

    @Test
    void plannerCreatesOneSessionPerSlotAndSkipsNonTeachingDays() {
        UUID slotId = UUID.randomUUID();
        List<WeeklySlot> slots = List.of(
                new WeeklySlot(slotId, DayOfWeek.MONDAY, LocalTime.of(7, 0), LocalTime.of(11, 0), "A-1"),
                new WeeklySlot(UUID.randomUUID(), DayOfWeek.WEDNESDAY, LocalTime.of(7, 0), LocalTime.of(9, 0), "A-1"));
        // Del lunes 2 al domingo 15 de noviembre de 2026: 2 lunes + 2 miércoles; el lunes 2 es festivo.
        LocalDate holiday = LocalDate.of(2026, 11, 2);
        var sessions = SessionPlanner.plan(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 15), slots,
                holiday::equals);
        assertThat(sessions).hasSize(3);
        assertThat(sessions).noneMatch(s -> s.date().equals(holiday));
        // Una sesión de 4 horas sigue siendo UNA sesión (la falta se cuenta por sesión).
        assertThat(sessions).filteredOn(s -> s.scheduleId().equals(slotId)).hasSize(1);
        assertThat(SessionPlanner.plan(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 1), slots, d -> false)).isEmpty();
    }

    @Test
    void policyResolutionPrefersMostSpecific() {
        UUID program = UUID.randomUUID();
        AttendancePolicy programPolicy = AttendancePolicy.forProgram(program, false, PolicyValues.DEFAULT, null, NOW);
        AttendancePolicy clinical = AttendancePolicy.forProgram(program, true, new PolicyValues(3, 1, 2, 2, null), null, NOW);
        AttendancePolicy subject = AttendancePolicy.forSubject(UUID.randomUUID(), new PolicyValues(6, 3, 5, 5, 3), null, NOW);

        assertThat(AttendancePolicy.resolve(Optional.of(subject), Optional.of(clinical), Optional.of(programPolicy),
                GroupType.PRACTICA_CLINICA).source()).isEqualTo(PolicySource.SUBJECT);
        assertThat(AttendancePolicy.resolve(Optional.empty(), Optional.of(clinical), Optional.of(programPolicy),
                GroupType.PRACTICA_CLINICA).maxAbsences()).isEqualTo(3);
        assertThat(AttendancePolicy.resolve(Optional.empty(), Optional.of(clinical), Optional.of(programPolicy),
                GroupType.REGULAR).source()).isEqualTo(PolicySource.PROGRAM);
        EffectiveAttendancePolicy fallback = AttendancePolicy.resolve(Optional.empty(), Optional.empty(), Optional.empty(),
                GroupType.REGULAR);
        assertThat(fallback.source()).isEqualTo(PolicySource.DEFAULT);
        assertThat(fallback.maxAbsences()).isEqualTo(4);
        assertThat(fallback.warningThreshold()).isEqualTo(2);
        assertThat(fallback.criticalThreshold()).isEqualTo(3);
        assertThat(fallback.countsPerSession()).isTrue();
        assertThat(fallback.justificationRequiresTeacherApproval()).isTrue();
        assertThat(subject.values().tardiesPerAbsence()).isEqualTo(3);
    }

    @Test
    void policyValuesEnforceThresholdOrder() {
        assertThatThrownBy(() -> new PolicyValues(4, 3, 3, 3, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new PolicyValues(4, 2, 4, 3, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new PolicyValues(4, 2, 3, 0, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new PolicyValues(4, 2, 3, 3, 1)).isInstanceOf(DomainException.class);
    }

    @Test
    void termAndSuspensionRules() {
        Term term = Term.create("2026-2", "Segundo", LocalDate.of(2026, 8, 3), LocalDate.of(2026, 11, 27),
                Term.Status.ACTIVE, NOW);
        assertThat(term.contains(LocalDate.of(2026, 9, 1))).isTrue();
        assertThat(term.contains(LocalDate.of(2026, 12, 1))).isFalse();
        term.update("Segundo", term.getStartDate(), term.getEndDate(), Term.Status.CLOSED, NOW);
        assertThatThrownBy(() -> term.update("x", term.getStartDate(), term.getEndDate(), Term.Status.ACTIVE, NOW))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Term.create("x", "x", LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 1),
                Term.Status.PLANNED, NOW)).isInstanceOf(DomainException.class);

        UUID program = UUID.randomUUID();
        ClassSuspension programWide = ClassSuspension.create(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), program,
                "Paro", null, NOW);
        assertThat(programWide.covers(LocalDate.of(2026, 9, 2), program)).isTrue();
        assertThat(programWide.covers(LocalDate.of(2026, 9, 2), UUID.randomUUID())).isFalse();
        ClassSuspension global = ClassSuspension.create(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1), null,
                "Elecciones", null, NOW);
        assertThat(global.covers(LocalDate.of(2026, 9, 1), UUID.randomUUID())).isTrue();
        assertThat(global.covers(LocalDate.of(2026, 9, 3), UUID.randomUUID())).isFalse();
    }

    @Test
    void subjectAndGroupRules() throws Exception {
        Subject subject = Subject.create(UUID.randomUUID(), "MED-1", "Anatomía", 4, 1, NOW);
        assertThat(subject.update("Anatomía", 4, 1, true, NOW)).isFalse();
        assertThat(subject.update("Anatomía I", 5, 1, true, NOW)).isTrue();
        assertThat(subject.replacePrerequisites(Set.of(UUID.randomUUID()), NOW)).isTrue();
        assertThatThrownBy(() -> subject.replacePrerequisites(Set.of(subject.getId()), NOW)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Subject.create(UUID.randomUUID(), "X", "X", 40, 1, NOW)).isInstanceOf(DomainException.class);

        Program law = program("DER", false);
        Program medicine = program("MED", true);
        assertThatThrownBy(() -> CourseGroup.create(subject, law, UUID.randomUUID(), "A", null, 30,
                GroupType.PRACTICA_CLINICA, NOW)).isInstanceOf(DomainException.class);
        CourseGroup group = CourseGroup.create(subject, medicine, UUID.randomUUID(), "A", null, 30,
                GroupType.PRACTICA_CLINICA, NOW);
        UUID teacher = UUID.randomUUID();
        assertThat(group.assignTeacher(teacher, NOW)).isNull();
        assertThat(group.taughtBy(teacher)).isTrue();
        assertThat(group.update(medicine, 30, GroupType.PRACTICA_CLINICA, NOW)).isFalse();
        assertThat(group.update(medicine, 35, GroupType.REGULAR, NOW)).isTrue();

        List<CourseGroup.Slot> schedule = List.of(
                new CourseGroup.Slot(DayOfWeek.MONDAY, LocalTime.of(7, 0), LocalTime.of(9, 0), "A"),
                new CourseGroup.Slot(DayOfWeek.WEDNESDAY, LocalTime.of(7, 0), LocalTime.of(9, 0), "A"));
        assertThat(group.replaceSchedule(schedule, NOW)).isTrue();
        assertThat(group.replaceSchedule(schedule, NOW)).isFalse();
        assertThat(group.getSchedules()).hasSize(2);
        assertThatThrownBy(() -> group.replaceSchedule(List.of(
                new CourseGroup.Slot(DayOfWeek.MONDAY, LocalTime.of(7, 0), LocalTime.of(9, 0), "A"),
                new CourseGroup.Slot(DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(10, 0), "B")), NOW))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> group.replaceSchedule(List.of(
                new CourseGroup.Slot(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(7, 0), "A")), NOW))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void sessionStatusTransitions() throws Exception {
        Constructor<ClassSession> ctor = ClassSession.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        ClassSession session = ctor.newInstance();
        var status = ClassSession.class.getDeclaredField("status");
        status.setAccessible(true);
        status.set(session, ClassSession.Status.PROGRAMADA);

        assertThatThrownBy(() -> session.changeStatus(ClassSession.Status.CANCELADA, " ", NOW))
                .isInstanceOf(DomainException.class);
        session.changeStatus(ClassSession.Status.CANCELADA, "Paro", NOW);
        assertThat(session.getCancellationReason()).isEqualTo("Paro");
        session.changeStatus(ClassSession.Status.PROGRAMADA, null, NOW);
        session.changeStatus(ClassSession.Status.DICTADA, null, NOW);
        assertThatThrownBy(() -> session.changeStatus(ClassSession.Status.CANCELADA, "x", NOW))
                .isInstanceOf(DomainException.class);
    }

    private static Program program(String code, boolean clinical) throws Exception {
        Constructor<Program> ctor = Program.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        Program program = ctor.newInstance();
        program.update("Programa " + code, clinical, true, NOW);
        var codeField = Program.class.getDeclaredField("code");
        codeField.setAccessible(true);
        codeField.set(program, code);
        return program;
    }
}
