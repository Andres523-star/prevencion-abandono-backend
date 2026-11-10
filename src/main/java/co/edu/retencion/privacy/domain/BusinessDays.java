package co.edu.retencion.privacy.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/** Suma días hábiles (sin sábados, domingos ni festivos), como cuenta la Ley 1581 sus plazos. */
public final class BusinessDays {

    private BusinessDays() {
    }

    public static LocalDate add(LocalDate from, int days, Set<LocalDate> holidays) {
        LocalDate date = from;
        int added = 0;
        while (added < days) {
            date = date.plusDays(1);
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY
                    && !holidays.contains(date)) {
                added++;
            }
        }
        return date;
    }
}
