package co.edu.retencion.imports.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mapeo configurable entre los encabezados del archivo y los campos canónicos. Como el formato
 * real de los datos aún no está definido, se aceptan (en este orden de prioridad):
 * <ol>
 *   <li>la sobrescritura del trabajo ({@code campo → encabezado}),</li>
 *   <li>el nombre canónico del campo,</li>
 *   <li>los alias configurados en {@code app.imports.aliases},</li>
 *   <li>los alias por defecto de la plantilla.</li>
 * </ol>
 * Los encabezados se comparan normalizados: sin tildes, en minúsculas y con espacios como "_".
 */
public final class ColumnMapping {

    private ColumnMapping() {
    }

    /**
     * @param columnIndex     posición de la columna de cada campo encontrado
     * @param missingRequired campos obligatorios sin columna
     */
    public record Resolution(Map<String, Integer> columnIndex, List<String> missingRequired) {

        public boolean isComplete() {
            return missingRequired.isEmpty();
        }
    }

    public static String normalize(String header) {
        if (header == null) {
            return "";
        }
        String noAccents = Normalizer.normalize(header.replace("﻿", ""), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return noAccents.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s\\-.]+", "_");
    }

    public static Resolution resolve(List<String> headers, List<FieldSpec> fields,
                                     Map<String, List<String>> configuredAliases, Map<String, String> override) {
        Map<String, Integer> byHeader = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            byHeader.putIfAbsent(normalize(headers.get(i)), i);
        }
        Map<String, Integer> index = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (FieldSpec field : fields) {
            List<String> candidates = new ArrayList<>();
            if (override.containsKey(field.name())) {
                candidates.add(override.get(field.name()));
            }
            candidates.add(field.name());
            candidates.addAll(configuredAliases.getOrDefault(field.name(), List.of()));
            candidates.addAll(field.aliases());
            Integer column = candidates.stream().map(ColumnMapping::normalize).map(byHeader::get)
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            if (column != null) {
                index.put(field.name(), column);
            } else if (field.required()) {
                missing.add(field.name());
            }
        }
        return new Resolution(index, missing);
    }
}
