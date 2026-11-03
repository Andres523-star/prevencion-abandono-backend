package co.edu.retencion.imports.infrastructure;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import co.edu.retencion.common.error.DomainException;

/**
 * <b>Adapter</b> de Apache Commons CSV. Acepta UTF-8 (con o sin BOM) y detecta el separador
 * (coma o punto y coma, este último típico de Excel en español).
 */
class CsvTableReader implements TableReader {

    @Override
    public Table read(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        String firstLine = text.lines().findFirst().orElse("");
        char delimiter = count(firstLine, ';') > count(firstLine, ',') ? ';' : ',';
        CSVFormat format = CSVFormat.RFC4180.builder().setDelimiter(delimiter)
                // Las líneas vacías no se ignoran en el parser para que el número de fila coincida con el
                // que ve el usuario; luego se descartan abajo.
                .setIgnoreEmptyLines(false)
                .setTrim(true).get();
        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            List<String> headers = null;
            List<Row> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                List<String> cells = record.toList();
                if (headers == null) {
                    headers = cells;
                    continue;
                }
                if (cells.stream().allMatch(String::isBlank)) {
                    continue;
                }
                if (rows.size() >= MAX_ROWS) {
                    throw DomainException.badRequest("El archivo supera el máximo de " + MAX_ROWS + " filas");
                }
                rows.add(new Row((int) record.getRecordNumber(), cells));
            }
            if (headers == null) {
                throw DomainException.badRequest("El archivo está vacío");
            }
            return new Table(headers, rows);
        } catch (IOException | IllegalStateException | java.io.UncheckedIOException ex) {
            throw DomainException.badRequest("El CSV no es válido");
        }
    }

    private static long count(String line, char c) {
        return line.chars().filter(ch -> ch == c).count();
    }
}
