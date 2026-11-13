package co.edu.permanencia.demo.analytics;

public final class CalculadoraTendenciaRiesgo {
    private CalculadoraTendenciaRiesgo() {}

    public enum Tendencia { MEJORANDO, ESTABLE, DETERIORANDO }

    public static Tendencia evaluarTendencia(int scoreAnterior, int scoreActual) {
        int diff = scoreActual - scoreAnterior;
        if (diff <= -5) return Tendencia.MEJORANDO;
        if (diff >= 5) return Tendencia.DETERIORANDO;
        return Tendencia.ESTABLE;
    }
}
