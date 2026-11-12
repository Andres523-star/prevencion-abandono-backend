package co.edu.permanencia.demo.validation;

public final class ValidadorCorreoInstitucional {
    private ValidadorCorreoInstitucional() {}

    public static boolean esCorreoUCC(String email) {
        if (email == null) return false;
        String regex = "^[a-zA-Z0-9._%+-]+@(campusucc\\.edu\\.co|ucc\\.edu\\.co)$";
        return email.trim().matches(regex);
    }
}
