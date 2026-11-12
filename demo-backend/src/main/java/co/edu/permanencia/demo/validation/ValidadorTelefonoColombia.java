package co.edu.permanencia.demo.validation;

public final class ValidadorTelefonoColombia {
    private ValidadorTelefonoColombia() {}

    public static boolean esTelefonoMovilValido(String telefono) {
        if (telefono == null) return false;
        String limpio = telefono.replaceAll("[\\s-]", "");
        return limpio.matches("^(\\+57)?3[0-9]{9}$");
    }
}
