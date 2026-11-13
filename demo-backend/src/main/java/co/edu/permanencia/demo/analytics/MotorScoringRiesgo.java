package co.edu.permanencia.demo.analytics;

public final class MotorScoringRiesgo {
    private MotorScoringRiesgo() {}

    public static int calcularScorePonderado(double rendimiento, double asistencia, double socioeconomico) {
        double score = (rendimiento * 0.45) + (asistencia * 0.35) + (socioeconomico * 0.20);
        return (int) Math.min(100, Math.max(0, Math.round(score)));
    }
}
