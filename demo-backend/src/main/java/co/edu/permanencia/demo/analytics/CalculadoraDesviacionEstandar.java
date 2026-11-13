package co.edu.permanencia.demo.analytics;

import java.util.List;

public final class CalculadoraDesviacionEstandar {
    private CalculadoraDesviacionEstandar() {}

    public static double calcularDesviacion(List<Double> valores) {
        if (valores == null || valores.size() <= 1) return 0.0;
        double media = valores.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double sumaCuadrados = valores.stream().mapToDouble(v -> Math.pow(v - media, 2)).sum();
        return Math.round(Math.sqrt(sumaCuadrados / (valores.size() - 1)) * 100.0) / 100.0;
    }
}
