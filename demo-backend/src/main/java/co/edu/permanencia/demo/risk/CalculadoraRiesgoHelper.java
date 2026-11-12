package co.edu.permanencia.demo.risk;

public final class CalculadoraRiesgoHelper {
    private CalculadoraRiesgoHelper() {}

    public static int normalizarPuntaje(double rawScore) {
        return (int) Math.min(100, Math.max(0, Math.round(rawScore)));
    }

    public static boolean esCasoPrioritario(int scoreRiesgo, int ausencias) {
        return scoreRiesgo >= 70 || ausencias >= 8;
    }
}
