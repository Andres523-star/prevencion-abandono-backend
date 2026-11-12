package co.edu.permanencia.demo.validation;

public final class ValidadorIdentificacionUCC {
    private ValidadorIdentificacionUCC() {}

    public static boolean esDocumentoValido(String documento) {
        if (documento == null) return false;
        String limpio = documento.trim();
        return limpio.matches("^[0-9]{6,12}$");
    }
}
