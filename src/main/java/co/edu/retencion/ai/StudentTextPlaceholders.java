package co.edu.retencion.ai;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Marcadores permitidos en los textos dirigidos al estudiante. La IA escribe {@code {{nombre}}} y el
 * backend lo reemplaza al mostrar el texto a un rol autorizado: el nombre nunca sale hacia la IA.
 * Cualquier otro marcador hace que la salida se rechace.
 */
public final class StudentTextPlaceholders {

    public static final String NOMBRE = "nombre";
    public static final String MATERIA = "materia";
    public static final Set<String> ALLOWED = Set.of(NOMBRE, MATERIA);

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^{}]*?)\\s*}}");

    private StudentTextPlaceholders() {
    }

    /** {@code true} si el texto solo usa marcadores permitidos. */
    public static boolean onlyAllowed(String text) {
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            if (!ALLOWED.contains(m.group(1))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Rellena los marcadores. Un marcador sin valor se reemplaza por un texto neutro para no dejar
     * llaves visibles.
     */
    public static String render(String template, String name, String subject) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = switch (m.group(1)) {
                case NOMBRE -> name == null || name.isBlank() ? "estudiante" : name;
                case MATERIA -> subject == null || subject.isBlank() ? "la materia" : subject;
                default -> "";
            };
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
