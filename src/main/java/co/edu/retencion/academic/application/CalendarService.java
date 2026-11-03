package co.edu.retencion.academic.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.academic.application.AcademicViews.HolidayView;
import co.edu.retencion.academic.application.AcademicViews.SuspensionView;
import co.edu.retencion.academic.application.AcademicViews.TermView;
import co.edu.retencion.academic.domain.ClassSuspension;
import co.edu.retencion.academic.domain.ClassSuspensionRepository;
import co.edu.retencion.academic.domain.ColombianHolidays;
import co.edu.retencion.academic.domain.Holiday;
import co.edu.retencion.academic.domain.HolidayRepository;
import co.edu.retencion.academic.domain.ProgramRepository;
import co.edu.retencion.academic.domain.Term;
import co.edu.retencion.academic.domain.TermRepository;
import co.edu.retencion.academic.infrastructure.SessionJdbcWriter;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.CurrentUser;

/**
 * Calendario académico: periodos, festivos y suspensiones de clase. Registrar un festivo o una
 * suspensión cancela las sesiones programadas que caen en esas fechas.
 */
@Service
public class CalendarService {

    private final TermRepository terms;
    private final HolidayRepository holidays;
    private final ClassSuspensionRepository suspensions;
    private final ProgramRepository programs;
    private final SessionJdbcWriter sessions;
    private final Clock clock;

    public CalendarService(TermRepository terms, HolidayRepository holidays, ClassSuspensionRepository suspensions,
                           ProgramRepository programs, SessionJdbcWriter sessions, Clock clock) {
        this.terms = terms;
        this.holidays = holidays;
        this.suspensions = suspensions;
        this.programs = programs;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<TermView> listTerms() {
        return terms.findAllByOrderByStartDateDesc().stream().map(TermView::of).toList();
    }

    @Transactional(readOnly = true)
    public TermView getTerm(UUID id) {
        return TermView.of(loadTerm(id));
    }

    @Transactional
    public TermView createTerm(String code, String name, LocalDate start, LocalDate end, Term.Status status) {
        if (terms.findByCode(code).isPresent()) {
            throw DomainException.conflict("Ya existe un periodo con el código " + code);
        }
        return TermView.of(terms.save(Term.create(code, name, start, end, status, clock.instant())));
    }

    @Transactional
    public TermView updateTerm(UUID id, String name, LocalDate start, LocalDate end, Term.Status status) {
        Term term = loadTerm(id);
        term.update(name, start, end, status, clock.instant());
        return TermView.of(terms.saveAndFlush(term));
    }

    @Transactional(readOnly = true)
    public List<HolidayView> holidays(int year) {
        return holidays.findByDateBetweenOrderByDateAsc(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
                .stream().map(HolidayView::of).toList();
    }

    @Transactional
    public HolidayView addHoliday(LocalDate date, String name) {
        Holiday holiday = holidays.save(new Holiday(date, name));
        sessions.cancelScheduled(date, date, null, "Festivo: " + name, clock.instant());
        return HolidayView.of(holiday);
    }

    /** Carga (idempotente) los festivos de Colombia del año indicado. */
    @Transactional
    public List<HolidayView> loadColombianHolidays(int year) {
        if (year < 2000 || year > 2100) {
            throw DomainException.badRequest("Año fuera de rango");
        }
        for (Map.Entry<LocalDate, String> entry : ColombianHolidays.forYear(year).entrySet()) {
            if (!holidays.existsById(entry.getKey())) {
                addHoliday(entry.getKey(), entry.getValue());
            }
        }
        return holidays(year);
    }

    @Transactional
    public void removeHoliday(LocalDate date) {
        if (!holidays.existsById(date)) {
            throw DomainException.notFound("No hay festivo en esa fecha");
        }
        holidays.deleteById(date);
    }

    @Transactional
    public SuspensionView suspend(LocalDate from, LocalDate to, UUID programId, String reason) {
        if (programId != null && !programs.existsById(programId)) {
            throw DomainException.notFound("Programa inexistente");
        }
        ClassSuspension suspension = suspensions.save(ClassSuspension.create(from, to, programId, reason,
                CurrentUser.require().userId(), clock.instant()));
        int cancelled = sessions.cancelScheduled(from, to, programId, "Suspensión: " + reason, clock.instant());
        return SuspensionView.of(suspension, cancelled);
    }

    @Transactional(readOnly = true)
    public List<SuspensionView> suspensions(LocalDate from, LocalDate to) {
        return suspensions.overlapping(from, to).stream().map(s -> SuspensionView.of(s, 0)).toList();
    }

    Term loadTerm(UUID id) {
        return terms.findById(id).orElseThrow(() -> DomainException.notFound("Periodo no encontrado"));
    }
}
