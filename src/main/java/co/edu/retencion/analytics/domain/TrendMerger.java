package co.edu.retencion.analytics.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Une la serie en vivo con la histórica en una sola línea de tiempo (servicio de dominio puro). Un
 * mismo periodo puede tener las dos solo por año: la serie histórica se corta antes del primer periodo
 * en vivo, pero ese año puede tener meses de ambas.
 */
public final class TrendMerger {

    private TrendMerger() {
    }

    /**
     * @param enrolled matriculados si se conocen; {@code null} si no
     */
    public record Point(LocalDate period, long dropouts, Long enrolled) {
    }

    /** Punto unido, con el origen de sus datos. */
    public record Merged(LocalDate period, long dropouts, Long enrolled, String source) {
    }

    public static List<Merged> merge(List<Point> live, List<Point> historical) {
        Map<LocalDate, Merged> byPeriod = new TreeMap<>();
        for (Point p : historical) {
            byPeriod.put(p.period(), new Merged(p.period(), p.dropouts(), p.enrolled(), "HISTORICAL"));
        }
        for (Point p : live) {
            Merged old = byPeriod.get(p.period());
            byPeriod.put(p.period(), old == null
                    ? new Merged(p.period(), p.dropouts(), p.enrolled(), "LIVE")
                    : new Merged(p.period(), old.dropouts() + p.dropouts(), add(old.enrolled(), p.enrolled()), "MIXED"));
        }
        return new ArrayList<>(byPeriod.values());
    }

    private static Long add(Long a, Long b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : Long.valueOf(a + b);
    }
}
