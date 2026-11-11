package co.edu.permanencia.demo.common;

public final class ConstantesRiesgo {
    private ConstantesRiesgo() {}

    public static final int UMBRAL_RIESGO_BAJO = 30;
    public static final int UMBRAL_RIESGO_ALTO = 70;

    public static final String NIVEL_BAJO = "Bajo";
    public static final String NIVEL_MEDIO = "Medio";
    public static final String NIVEL_ALTO = "Alto";

    public static String clasificarNivel(int score) {
        if (score >= UMBRAL_RIESGO_ALTO) return NIVEL_ALTO;
        if (score >= UMBRAL_RIESGO_BAJO) return NIVEL_MEDIO;
        return NIVEL_BAJO;
    }
}
