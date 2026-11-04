package co.edu.retencion.records.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/**
 * Días hábiles: lunes a viernes que no son festivos. Se usa para el plazo de justificación.
 */
public final class BusinessDays {

    private BusinessDays() {
    }

    /** Fecha que resulta de sumar {@code days} días hábiles a {@code from} (sin contar {@code from}). */
    public static LocalDate add(LocalDate from, int days, Set<LocalDate> holidays) {
        LocalDate date = from;
        int added = 0;
        while (added < days) {
            date = date.plusDays(1);
            if (isBusinessDay(date, holidays)) {
                added++;
            }
        }
        return date;
    }

    public static boolean isBusinessDay(LocalDate date, Set<LocalDate> holidays) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY && !holidays.contains(date);
    }
}
