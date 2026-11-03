package co.edu.retencion.academic.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import co.edu.retencion.academic.domain.ClassSuspension;
import co.edu.retencion.academic.domain.ClassSuspensionRepository;
import co.edu.retencion.academic.domain.CourseGroup;
import co.edu.retencion.academic.domain.Holiday;
import co.edu.retencion.academic.domain.HolidayRepository;
import co.edu.retencion.academic.domain.SessionPlanner;
import co.edu.retencion.academic.domain.SessionPlanner.PlannedSession;
import co.edu.retencion.academic.domain.SessionPlanner.WeeklySlot;
import co.edu.retencion.academic.domain.Term;
import co.edu.retencion.academic.infrastructure.SessionJdbcWriter;

/**
 * Genera las sesiones de un grupo a partir de su horario y del calendario del periodo,
 * omitiendo festivos y suspensiones (globales o del programa). Es idempotente: volver a
 * generar no duplica sesiones.
 */
@Component
class SessionGeneration {

    private final HolidayRepository holidays;
    private final ClassSuspensionRepository suspensions;
    private final SessionJdbcWriter writer;
    private final Clock clock;

    SessionGeneration(HolidayRepository holidays, ClassSuspensionRepository suspensions, SessionJdbcWriter writer,
                      Clock clock) {
        this.holidays = holidays;
        this.suspensions = suspensions;
        this.writer = writer;
        this.clock = clock;
    }

    /** Calendario no lectivo de un periodo, reutilizable para todos sus grupos. */
    record NonTeachingCalendar(Set<LocalDate> holidayDates, List<ClassSuspension> suspensions) {

        boolean isNonTeaching(LocalDate date, UUID programId) {
            return holidayDates.contains(date) || suspensions.stream().anyMatch(s -> s.covers(date, programId));
        }
    }

    NonTeachingCalendar calendarFor(Term term) {
        Set<LocalDate> dates = holidays.findByDateBetweenOrderByDateAsc(term.getStartDate(), term.getEndDate())
                .stream().map(Holiday::getDate).collect(Collectors.toSet());
        return new NonTeachingCalendar(dates, suspensions.overlapping(term.getStartDate(), term.getEndDate()));
    }

    int generate(CourseGroup group, Term term, UUID programId, NonTeachingCalendar calendar) {
        return generate(group, term, programId, calendar, term.getStartDate());
    }

    /** Genera desde {@code from} (p. ej. hoy, al cambiar el horario, para no crear sesiones pasadas). */
    int generate(CourseGroup group, Term term, UUID programId, NonTeachingCalendar calendar, LocalDate from) {
        List<WeeklySlot> slots = group.getSchedules().stream()
                .map(s -> new WeeklySlot(s.getId(), s.day(), s.getStartTime(), s.getEndTime(), s.getRoom()))
                .toList();
        List<PlannedSession> planned = SessionPlanner.plan(from.isAfter(term.getStartDate()) ? from : term.getStartDate(), term.getEndDate(), slots,
                date -> calendar.isNonTeaching(date, programId));
        return writer.insertMissing(group.getId(), planned, clock.instant());
    }
}
