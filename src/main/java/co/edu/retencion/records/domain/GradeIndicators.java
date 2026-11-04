package co.edu.retencion.records.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Indicadores de riesgo a partir de las notas registradas (servicio de dominio puro):
 * promedio simple por materia y por semestre, materias por debajo de 3.0 y tendencia.
 *
 * <p>Tendencia: con 3 o más notas se compara el promedio de las 2 últimas con el de las
 * anteriores; con 2 notas, la última contra la primera. Una diferencia de ±0.3 o más es
 * SUBE/BAJA; menos, ESTABLE; con una sola nota, INSUFICIENTE.
 */
public final class GradeIndicators {

    static final BigDecimal TREND_DELTA = new BigDecimal("0.3");

    private GradeIndicators() {
    }

    /** Tendencia de las notas. */
    public enum Trend {
        SUBE,
        ESTABLE,
        BAJA,
        INSUFICIENTE
    }

    /**
     * @param groupId    grupo (materia del periodo)
     * @param value      nota
     * @param assessedOn fecha
     */
    public record Point(UUID groupId, BigDecimal value, LocalDate assessedOn) {
    }

    /**
     * @param groupId       grupo
     * @param average       promedio simple (2 decimales)
     * @param gradeCount    notas registradas
     * @param belowPassing  promedio por debajo de 3.0
     * @param trend         tendencia
     * @param lastGrade     última nota
     */
    public record SubjectIndicator(UUID groupId, BigDecimal average, int gradeCount, boolean belowPassing, Trend trend,
                                   BigDecimal lastGrade) {
    }

    /**
     * @param subjects             indicadores por materia
     * @param semesterAverage      promedio de los promedios por materia ({@code null} sin notas)
     * @param subjectsBelowPassing materias con promedio menor a 3.0
     */
    public record Summary(List<SubjectIndicator> subjects, BigDecimal semesterAverage, int subjectsBelowPassing) {
    }

    public static Summary compute(List<Point> points) {
        Map<UUID, List<Point>> byGroup = new LinkedHashMap<>();
        points.stream().sorted(Comparator.comparing(Point::assessedOn))
                .forEach(p -> byGroup.computeIfAbsent(p.groupId(), k -> new ArrayList<>()).add(p));
        List<SubjectIndicator> subjects = new ArrayList<>();
        for (Map.Entry<UUID, List<Point>> entry : byGroup.entrySet()) {
            List<BigDecimal> values = entry.getValue().stream().map(Point::value).toList();
            BigDecimal average = mean(values);
            subjects.add(new SubjectIndicator(entry.getKey(), average, values.size(),
                    average.compareTo(Grade.PASSING) < 0, trend(values), values.get(values.size() - 1)));
        }
        BigDecimal semester = subjects.isEmpty() ? null : mean(subjects.stream().map(SubjectIndicator::average).toList());
        int below = (int) subjects.stream().filter(SubjectIndicator::belowPassing).count();
        return new Summary(subjects, semester, below);
    }

    static Trend trend(List<BigDecimal> chronological) {
        int n = chronological.size();
        if (n < 2) {
            return Trend.INSUFICIENTE;
        }
        BigDecimal recent;
        BigDecimal earlier;
        if (n == 2) {
            recent = chronological.get(1);
            earlier = chronological.get(0);
        } else {
            recent = mean(chronological.subList(n - 2, n));
            earlier = mean(chronological.subList(0, n - 2));
        }
        BigDecimal delta = recent.subtract(earlier);
        if (delta.compareTo(TREND_DELTA) >= 0) {
            return Trend.SUBE;
        }
        if (delta.compareTo(TREND_DELTA.negate()) <= 0) {
            return Trend.BAJA;
        }
        return Trend.ESTABLE;
    }

    static BigDecimal mean(List<BigDecimal> values) {
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }
}
