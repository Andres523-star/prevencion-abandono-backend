package co.edu.retencion.imports.domain;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Valores de una fila ya mapeados a campos canónicos, con conversión tipada. Los errores se
 * acumulan (se reportan todos los de la fila a la vez) y NUNCA incluyen el valor recibido, solo
 * el campo y qué se esperaba.
 */
public final class RowValues {

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("d/M/uuuu"), DateTimeFormatter.ofPattern("d-M-uuuu"));
    private static final Set<String> TRUE = Set.of("si", "sí", "s", "true", "1", "x", "yes");
    private static final Set<String> FALSE = Set.of("no", "n", "false", "0");

    private final int rowNumber;
    private final Map<String, String> values;
    private final List<String> errors = new ArrayList<>();

    public RowValues(int rowNumber, Map<String, String> values) {
        this.rowNumber = rowNumber;
        this.values = values;
    }

    public int rowNumber() {
        return rowNumber;
    }

    public String optional(String field) {
        String value = values.get(field);
        return value == null || value.isBlank() ? null : value.trim();
    }

    public String required(String field) {
        String value = optional(field);
        if (value == null) {
            errors.add(field + ": es obligatorio");
        }
        return value;
    }

    public Integer optionalInt(String field, int min, int max) {
        String value = optional(field);
        if (value == null) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.replaceAll("\\.0+$", ""));
            if (parsed < min || parsed > max) {
                errors.add(field + ": debe estar entre " + min + " y " + max);
                return null;
            }
            return parsed;
        } catch (NumberFormatException ex) {
            errors.add(field + ": debe ser un número entero");
            return null;
        }
    }

    public Integer requiredInt(String field, int min, int max) {
        if (optional(field) == null) {
            errors.add(field + ": es obligatorio");
            return null;
        }
        return optionalInt(field, min, max);
    }

    public Boolean optionalBool(String field) {
        String value = optional(field);
        if (value == null) {
            return null;
        }
        String v = value.toLowerCase(Locale.ROOT);
        if (TRUE.contains(v)) {
            return true;
        }
        if (FALSE.contains(v)) {
            return false;
        }
        errors.add(field + ": use sí/no");
        return null;
    }

    public LocalDate requiredDate(String field) {
        String value = required(field);
        if (value == null) {
            return null;
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, format);
            } catch (DateTimeParseException ignored) {
                // se intenta el siguiente formato
            }
        }
        errors.add(field + ": fecha inválida (use AAAA-MM-DD o DD/MM/AAAA)");
        return null;
    }

    public <E extends Enum<E>> E enumValue(String field, Class<E> type, E defaultValue) {
        String value = optional(field);
        if (value == null) {
            if (defaultValue == null) {
                errors.add(field + ": es obligatorio");
            }
            return defaultValue;
        }
        String normalized = ColumnMapping.normalize(value).toUpperCase(Locale.ROOT);
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(normalized)) {
                return constant;
            }
        }
        errors.add(field + ": valor no permitido");
        return defaultValue;
    }

    public void addError(String message) {
        errors.add(message);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public String errorMessage() {
        return String.join("; ", errors);
    }
}
