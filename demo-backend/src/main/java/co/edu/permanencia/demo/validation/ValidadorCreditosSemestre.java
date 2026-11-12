package co.edu.permanencia.demo.validation;

public final class ValidadorCreditosSemestre {
    private ValidadorCreditosSemestre() {}

    public static final int MIN_CREDITOS = 8;
    public static final int MAX_CREDITOS = 22;

    public static boolean estaEnRangoCreditos(int creditos) {
        return creditos >= MIN_CREDITOS && creditos <= MAX_CREDITOS;
    }
}
