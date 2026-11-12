package co.edu.permanencia.demo.validation;

public final class ValidadorNotasUCC {
    private ValidadorNotasUCC() {}

    public static boolean esNotaValida(double nota) {
        return nota >= 0.0 && nota <= 5.0;
    }

    public static boolean esNotaAprobatoria(double nota) {
        return nota >= 3.0 && nota <= 5.0;
    }
}
