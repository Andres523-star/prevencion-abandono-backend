package co.edu.retencion.academic.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Servicio de dominio puro: calcula las sesiones de clase de un grupo a partir de su horario
 * semanal y un rango de fechas, omitiendo festivos y días con suspensión de clases.
 */
public final class SessionPlanner {

    private SessionPlanner() {
    }

    /**
     * Franja del horario.
     *
     * @param scheduleId id de la franja
     * @param day        día de la semana
     * @param start      inicio
     * @param end        fin
     * @param room       aula
     */
    public record WeeklySlot(UUID scheduleId, DayOfWeek day, LocalTime start, LocalTime end, String room) {
    }

    /**
     * Sesión calculada.
     *
     * @param scheduleId franja de origen
     * @param date       fecha
     * @param start      inicio
     * @param end        fin
     * @param room       aula
     */
    public record PlannedSession(UUID scheduleId, LocalDate date, LocalTime start, LocalTime end, String room) {
    }

    /**
     * @param from          primer día (inclusive)
     * @param to            último día (inclusive)
     * @param slots         horario semanal
     * @param nonTeaching   devuelve {@code true} para festivos o días suspendidos
     */
    public static List<PlannedSession> plan(LocalDate from, LocalDate to, List<WeeklySlot> slots,
                                            Predicate<LocalDate> nonTeaching) {
        List<PlannedSession> sessions = new ArrayList<>();
        if (slots.isEmpty() || to.isBefore(from)) {
            return sessions;
        }
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (nonTeaching.test(date)) {
                continue;
            }
            for (WeeklySlot slot : slots) {
                if (slot.day() == date.getDayOfWeek()) {
                    sessions.add(new PlannedSession(slot.scheduleId(), date, slot.start(), slot.end(), slot.room()));
                }
            }
        }
        return sessions;
    }
}
