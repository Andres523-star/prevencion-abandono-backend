package co.edu.retencion.students.domain;

import java.security.SecureRandom;

/**
 * Genera el código seudónimo del estudiante ({@code anon_code}), p. ej. {@code EST-7K2M9QX4TB}.
 *
 * <p>No es predecible ni derivable de los datos del estudiante: son 50 bits de
 * {@link SecureRandom} en Base32 de Crockford (sin I, L, O ni U para evitar confusiones al
 * dictarlo). La unicidad la garantiza la restricción UNIQUE de la base de datos.
 */
public final class AnonCodeGenerator {

    public static final String PREFIX = "EST-";
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private AnonCodeGenerator() {
    }

    public static String next() {
        char[] code = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            code[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return PREFIX + new String(code);
    }

    public static boolean isValid(String value) {
        return value != null && value.matches("^EST-[0-9A-HJKMNP-TV-Z]{10}$");
    }
}
