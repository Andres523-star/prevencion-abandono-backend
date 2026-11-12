package co.edu.permanencia.demo.reports;

import java.util.List;

public final class EstadisticasHelper {
    private EstadisticasHelper() {}

    public static double calcularPromedio(List<Double> valores) {
        if (valores == null || valores.isEmpty()) return 0.0;
        double sum = 0.0;
        for (double v : valores) sum += v;
        return Math.round((sum / valores.size()) * 100.0) / 100.0;
    }
}
