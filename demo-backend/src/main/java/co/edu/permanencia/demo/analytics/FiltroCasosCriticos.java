package co.edu.permanencia.demo.analytics;

public final class FiltroCasosCriticos {
    private FiltroCasosCriticos() {}

    public static boolean esCasoUrgente(int riesgoScore, double promedio, int faltas) {
        return riesgoScore >= 75 || promedio < 2.8 || faltas >= 8;
    }
}
