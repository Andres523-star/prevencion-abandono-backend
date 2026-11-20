package co.edu.permanencia.demo.risk;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Redactor de datos personales para texto libre antes de enviarlo a la IA (versión reducida del
 * {@code PiiRedactor} del backend principal).
 *
 * <p>Reemplaza correos, URL, usuarios (@alguien), teléfonos, números de documento y el nombre y
 * apellidos del propio estudiante. Se usa solo si {@code demo.ai.free-text-enabled=true}; por
 * defecto el texto libre ni siquiera se envía.</p>
 */
final class RedactorPii {

    private static final Pattern CORREO = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    private static final Pattern URL = Pattern.compile("(?i)\\b(https?://|www\\.)\\S+");
    private static final Pattern USUARIO = Pattern.compile("(?<![\\w.])@\\w{2,}");
    /** Secuencias de 6 o más dígitos (con espacios, puntos o guiones): teléfonos y documentos. */
    private static final Pattern NUMERO = Pattern.compile("\\+?\\d[\\d .-]{4,}\\d");

    private RedactorPii() {
    }

    static String redactar(String texto, String nombre, String apellido) {
        if (texto == null || texto.isBlank()) {
            return "";
        }
        String limpio = CORREO.matcher(texto).replaceAll("[correo]");
        limpio = URL.matcher(limpio).replaceAll("[enlace]");
        limpio = USUARIO.matcher(limpio).replaceAll("[usuario]");
        limpio = NUMERO.matcher(limpio).replaceAll("[número]");
        limpio = quitarPalabras(limpio, nombre);
        limpio = quitarPalabras(limpio, apellido);
        return limpio.strip();
    }

    /** Quita cada palabra del nombre (con o sin tildes, sin importar mayúsculas). */
    private static String quitarPalabras(String texto, String palabras) {
        if (palabras == null || palabras.isBlank()) {
            return texto;
        }
        String resultado = texto;
        for (String palabra : palabras.trim().split("\\s+")) {
            if (palabra.length() < 2) {
                continue;
            }
            String patron = sinTildes(palabra).chars()
                    .mapToObj(c -> claseConTildes((char) c))
                    .reduce("", String::concat);
            resultado = Pattern.compile("(?iu)(?<!\\p{L})" + patron + "(?!\\p{L})").matcher(resultado)
                    .replaceAll("[nombre]");
        }
        return resultado;
    }

    private static String claseConTildes(char c) {
        return switch (Character.toLowerCase(c)) {
            case 'a' -> "[aáàä]";
            case 'e' -> "[eéèë]";
            case 'i' -> "[iíìï]";
            case 'o' -> "[oóòö]";
            case 'u' -> "[uúùü]";
            case 'n' -> "[nñ]";
            default -> Pattern.quote(String.valueOf(c));
        };
    }

    private static String sinTildes(String valor) {
        return Normalizer.normalize(valor, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
