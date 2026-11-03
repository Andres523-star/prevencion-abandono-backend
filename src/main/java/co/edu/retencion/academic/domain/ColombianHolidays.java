package co.edu.retencion.academic.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.TreeMap;

/**
 * Calendario de festivos de Colombia (Ley 51 de 1983, "Ley Emiliani"):
 * <ul>
 *   <li>Fijos: 1 ene, 1 may, 20 jul, 7 ago, 8 dic, 25 dic.</li>
 *   <li>Trasladables al lunes siguiente: Reyes (6 ene), San José (19 mar), San Pedro y San Pablo
 *       (29 jun), Asunción (15 ago), Día de la Raza (12 oct), Todos los Santos (1 nov) e
 *       Independencia de Cartagena (11 nov).</li>
 *   <li>Según la Pascua: Jueves y Viernes Santo (fijos) y Ascensión, Corpus Christi y Sagrado
 *       Corazón (trasladados al lunes: Pascua + 43, + 64 y + 71 días).</li>
 * </ul>
 */
public final class ColombianHolidays {

    private ColombianHolidays() {
    }

    public static Map<LocalDate, String> forYear(int year) {
        Map<LocalDate, String> holidays = new TreeMap<>();
        holidays.put(LocalDate.of(year, 1, 1), "Año Nuevo");
        holidays.put(LocalDate.of(year, 5, 1), "Día del Trabajo");
        holidays.put(LocalDate.of(year, 7, 20), "Día de la Independencia");
        holidays.put(LocalDate.of(year, 8, 7), "Batalla de Boyacá");
        holidays.put(LocalDate.of(year, 12, 8), "Inmaculada Concepción");
        holidays.put(LocalDate.of(year, 12, 25), "Navidad");

        holidays.put(nextMonday(LocalDate.of(year, 1, 6)), "Día de los Reyes Magos");
        holidays.put(nextMonday(LocalDate.of(year, 3, 19)), "Día de San José");
        holidays.put(nextMonday(LocalDate.of(year, 6, 29)), "San Pedro y San Pablo");
        holidays.put(nextMonday(LocalDate.of(year, 8, 15)), "La Asunción de la Virgen");
        holidays.put(nextMonday(LocalDate.of(year, 10, 12)), "Día de la Raza");
        holidays.put(nextMonday(LocalDate.of(year, 11, 1)), "Todos los Santos");
        holidays.put(nextMonday(LocalDate.of(year, 11, 11)), "Independencia de Cartagena");

        LocalDate easter = easterSunday(year);
        holidays.put(easter.minusDays(3), "Jueves Santo");
        holidays.put(easter.minusDays(2), "Viernes Santo");
        holidays.put(easter.plusDays(43), "Ascensión del Señor");
        holidays.put(easter.plusDays(64), "Corpus Christi");
        holidays.put(easter.plusDays(71), "Sagrado Corazón");
        return holidays;
    }

    /** Domingo de Pascua (algoritmo gregoriano anónimo de Meeus/Jones/Butcher). */
    static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }

    private static LocalDate nextMonday(LocalDate date) {
        return date.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }
}
