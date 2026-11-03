package co.edu.retencion.imports.domain;

import java.util.List;

/**
 * Campo canónico de una plantilla de importación.
 *
 * @param name     nombre canónico (columna de la plantilla oficial)
 * @param required si la columna debe existir y la celda tener valor
 * @param aliases  encabezados alternativos aceptados por defecto (p. ej. "cedula" para "documento")
 * @param example  valor de ejemplo para la plantilla descargable
 */
public record FieldSpec(String name, boolean required, List<String> aliases, String example) {

    public static FieldSpec required(String name, String example, String... aliases) {
        return new FieldSpec(name, true, List.of(aliases), example);
    }

    public static FieldSpec optional(String name, String example, String... aliases) {
        return new FieldSpec(name, false, List.of(aliases), example);
    }
}
