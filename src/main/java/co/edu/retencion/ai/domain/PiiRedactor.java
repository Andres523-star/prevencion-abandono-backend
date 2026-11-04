package co.edu.retencion.ai.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Redactor de PII para texto libre (servicio de dominio puro): expresiones regulares para correos,
 * URL, usuarios ({@code @usuario}), teléfonos y números de documento, más un diccionario de nombres
 * y apellidos frecuentes en Colombia.
 *
 * <p>Por qué así: el texto libre de un docente ("hablé con Juan Pérez, su mamá llamó al 3001234567")
 * es el canal más probable de fuga de datos personales hacia la IA. Se prefiere redactar de más
 * (algún "Luna" o "Castillo" legítimo) que de menos. Las palabras del diccionario que también son
 * palabras comunes ("luz", "paz", "mora"...) solo se redactan si van con mayúscula inicial. Tras un
 * nombre, las palabras con mayúscula que lo siguen se tratan como apellidos.
 */
public final class PiiRedactor {

    public static final String EMAIL_TOKEN = "[CORREO]";
    public static final String URL_TOKEN = "[ENLACE]";
    public static final String NUMBER_TOKEN = "[NUMERO]";
    public static final String NAME_TOKEN = "[NOMBRE]";
    public static final String HANDLE_TOKEN = "[USUARIO]";

    private static final Pattern EMAIL = Pattern.compile("[\\p{L}0-9._%+-]+@[\\p{L}0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");
    private static final Pattern HANDLE = Pattern.compile("(?<![\\p{L}0-9])@[\\p{L}0-9_.]{2,}");
    /** Documentos y teléfonos: 6 o más dígitos seguidos, con o sin separadores ({@code 300 123 4567}, {@code 1.020.304.050}, {@code +57}). */
    private static final Pattern NUMBER = Pattern.compile(
            "(?<![\\p{L}0-9])\\+?\\d(?:[\\d .\\-]{4,}\\d)(?![\\p{L}0-9])");
    private static final Pattern WORD = Pattern.compile("\\p{L}+");

    private final Set<String> names;
    private final Set<String> ambiguous;

    /**
     * @param names     nombres y apellidos (cualquier forma: se normalizan sin tildes y en minúscula)
     * @param ambiguous los que también son palabras comunes (solo cuentan con mayúscula inicial)
     */
    public PiiRedactor(Set<String> names, Set<String> ambiguous) {
        this.names = names.stream().map(PiiRedactor::normalize).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        this.ambiguous = ambiguous.stream().map(PiiRedactor::normalize).collect(Collectors.toSet());
    }

    /** Reemplaza la PII detectada por marcadores neutros. */
    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = EMAIL.matcher(text).replaceAll(EMAIL_TOKEN);
        out = URL.matcher(out).replaceAll(URL_TOKEN);
        out = HANDLE.matcher(out).replaceAll(HANDLE_TOKEN);
        out = replaceNumbers(out);
        return redactNames(out);
    }

    /** Tipos de PII presentes (vacío si el texto está limpio). */
    public Set<String> detect(String text) {
        Set<String> kinds = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) {
            return kinds;
        }
        if (EMAIL.matcher(text).find()) {
            kinds.add("CORREO");
        }
        if (URL.matcher(text).find()) {
            kinds.add("ENLACE");
        }
        if (HANDLE.matcher(text).find()) {
            kinds.add("USUARIO");
        }
        Matcher numbers = NUMBER.matcher(text);
        while (numbers.find()) {
            if (digits(numbers.group()) >= 6) {
                kinds.add("TELEFONO_O_DOCUMENTO");
                break;
            }
        }
        if (!nameSpans(text).isEmpty()) {
            kinds.add("NOMBRE");
        }
        return kinds;
    }

    private static String replaceNumbers(String text) {
        Matcher m = NUMBER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, digits(m.group()) >= 6 ? NUMBER_TOKEN : Matcher.quoteReplacement(m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    private String redactNames(String text) {
        List<int[]> spans = nameSpans(text);
        if (spans.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        for (int[] span : spans) {
            out.append(text, cursor, span[0]).append(NAME_TOKEN);
            cursor = span[1];
        }
        return out.append(text.substring(cursor)).toString();
    }

    /** Tramos [inicio, fin) de nombres: palabra del diccionario más los apellidos con mayúscula que la siguen. */
    private List<int[]> nameSpans(String text) {
        List<int[]> spans = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        List<int[]> words = new ArrayList<>();
        while (m.find()) {
            words.add(new int[] {m.start(), m.end()});
        }
        int i = 0;
        while (i < words.size()) {
            int[] w = words.get(i);
            String token = text.substring(w[0], w[1]);
            if (isName(token)) {
                int end = w[1];
                int j = i + 1;
                // Hasta dos apellidos con mayúscula separados por un espacio.
                while (j < words.size() && j <= i + 2) {
                    int[] next = words.get(j);
                    String between = text.substring(end, next[0]);
                    String candidate = text.substring(next[0], next[1]);
                    if (!" ".equals(between) || !Character.isUpperCase(candidate.charAt(0))) {
                        break;
                    }
                    end = next[1];
                    j++;
                }
                spans.add(new int[] {w[0], end});
                i = j;
            } else {
                i++;
            }
        }
        return spans;
    }

    private boolean isName(String token) {
        if (token.length() < 3) {
            return false;
        }
        String key = normalize(token);
        if (!names.contains(key)) {
            return false;
        }
        return !ambiguous.contains(key) || Character.isUpperCase(token.charAt(0));
    }

    private static int digits(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) {
                n++;
            }
        }
        return n;
    }

    static String normalize(String s) {
        String stripped = Normalizer.normalize(s.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return stripped.toLowerCase(Locale.ROOT);
    }
}
