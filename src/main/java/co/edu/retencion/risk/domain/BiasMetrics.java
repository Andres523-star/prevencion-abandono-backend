package co.edu.retencion.risk.domain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import co.edu.retencion.risk.RiskLevel;

/**
 * Métricas básicas de sesgo (servicio de dominio puro): distribución del último nivel de riesgo
 * por programa, banda de estrato y semestre. Las celdas con menos de {@code minCell} estudiantes se
 * suprimen para evitar reidentificación.
 */
public final class BiasMetrics {

    private BiasMetrics() {
    }

    /**
     * Último nivel de un estudiante con sus atributos de grupo.
     *
     * @param program  código del programa
     * @param stratum  estrato ({@code null} si se desconoce)
     * @param semester semestre
     * @param level    último nivel
     */
    public record Row(String program, Integer stratum, int semester, RiskLevel level) {
    }

    /**
     * @param group      valor del grupo (p. ej. "ISW", "1-2", "3")
     * @param students   estudiantes del grupo ({@code null} si se suprimió)
     * @param byLevel    conteo por nivel (vacío si se suprimió)
     * @param highShare  proporción en ALTO ({@code null} si se suprimió)
     * @param suppressed la celda tiene menos del mínimo
     */
    public record Cell(String group, Integer students, Map<RiskLevel, Integer> byLevel, Double highShare,
                       boolean suppressed) {
    }

    /**
     * @param byProgram    distribución por programa
     * @param byStratum    distribución por banda de estrato (1-2, 3-4, 5-6, SIN_DATO)
     * @param bySemester   distribución por semestre
     * @param minCell      tamaño mínimo de celda aplicado
     * @param maxHighShareGapByStratum diferencia máxima de proporción en ALTO entre bandas de estrato visibles
     */
    public record Report(List<Cell> byProgram, List<Cell> byStratum, List<Cell> bySemester, int minCell,
                         Double maxHighShareGapByStratum) {
    }

    public static Report compute(List<Row> rows, int minCell) {
        List<Cell> byStratum = distribution(rows, r -> stratumBand(r.stratum()), minCell);
        Double gap = null;
        List<Double> shares = byStratum.stream().filter(c -> !c.suppressed() && !"SIN_DATO".equals(c.group()))
                .map(Cell::highShare).toList();
        if (shares.size() >= 2) {
            gap = Math.round((shares.stream().mapToDouble(d -> d).max().orElse(0)
                    - shares.stream().mapToDouble(d -> d).min().orElse(0)) * 10_000d) / 10_000d;
        }
        return new Report(distribution(rows, Row::program, minCell), byStratum,
                distribution(rows, r -> String.valueOf(r.semester()), minCell), minCell, gap);
    }

    static String stratumBand(Integer stratum) {
        if (stratum == null) {
            return "SIN_DATO";
        }
        return stratum <= 2 ? "1-2" : stratum <= 4 ? "3-4" : "5-6";
    }

    private static List<Cell> distribution(List<Row> rows, Function<Row, String> key, int minCell) {
        Map<String, Map<RiskLevel, Integer>> groups = new TreeMap<>();
        for (Row r : rows) {
            groups.computeIfAbsent(key.apply(r), k -> {
                Map<RiskLevel, Integer> m = new EnumMap<>(RiskLevel.class);
                for (RiskLevel l : RiskLevel.values()) {
                    m.put(l, 0);
                }
                return m;
            }).merge(r.level(), 1, Integer::sum);
        }
        List<Cell> cells = new ArrayList<>();
        groups.forEach((group, counts) -> {
            int total = counts.values().stream().mapToInt(Integer::intValue).sum();
            if (total < minCell) {
                cells.add(new Cell(group, null, Map.of(), null, true));
            } else {
                double share = Math.round(counts.get(RiskLevel.ALTO) * 10_000d / total) / 10_000d;
                cells.add(new Cell(group, total, Map.copyOf(counts), share, false));
            }
        });
        return cells;
    }
}
