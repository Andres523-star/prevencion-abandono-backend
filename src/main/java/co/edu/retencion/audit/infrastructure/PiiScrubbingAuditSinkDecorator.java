package co.edu.retencion.audit.infrastructure;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import co.edu.retencion.audit.domain.AuditEntry;
import co.edu.retencion.audit.domain.AuditEventSink;

/**
 * <b>Patrón estructural: Decorator.</b> Envuelve otro {@link AuditEventSink} y, antes de delegar,
 * depura datos personales de los detalles y del motivo: elimina claves con nombres sospechosos
 * (correo, nombre, documento, token...) y enmascara valores con forma de correo, JWT o números
 * largos (documentos, teléfonos). Es una red de seguridad: los llamadores ya no deberían enviar PII.
 */
public class PiiScrubbingAuditSinkDecorator implements AuditEventSink {

    static final String REDACTED = "[REDACTADO]";
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(email|correo|mail|name|nombre|document|documento|cedula|phone|telefono|celular|token|password|secret|clave).*");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*");
    // Números de 7+ cifras (documentos, teléfonos) que no formen parte de un UUID o hash hexadecimal.
    private static final Pattern LONG_NUMBER = Pattern.compile("(?<![0-9A-Fa-f-])\\d[\\d .-]{5,}\\d(?![0-9A-Fa-f-])");
    private static final int MAX_VALUE_LENGTH = 200;

    private final AuditEventSink delegate;

    public PiiScrubbingAuditSinkDecorator(AuditEventSink delegate) {
        this.delegate = delegate;
    }

    @Override
    public AuditEntry append(AuditEntry entry) {
        Map<String, String> clean = new LinkedHashMap<>();
        entry.details().forEach((key, value) ->
                clean.put(key, SENSITIVE_KEY.matcher(key).matches() ? REDACTED : scrub(value)));
        return delegate.append(entry.toBuilder()
                .details(clean)
                .reason(scrub(entry.reason()))
                .build());
    }

    static String scrub(String value) {
        if (value == null) {
            return null;
        }
        String result = JWT.matcher(value).replaceAll(REDACTED);
        result = EMAIL.matcher(result).replaceAll(REDACTED);
        result = LONG_NUMBER.matcher(result).replaceAll(REDACTED);
        return result.length() > MAX_VALUE_LENGTH ? result.substring(0, MAX_VALUE_LENGTH) : result;
    }
}
