package co.edu.retencion.imports.infrastructure;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import co.edu.retencion.common.error.DomainException;

/**
 * <b>Adapter</b> de Apache POI para Excel (.xlsx). Lee la primera hoja; las fechas y números se
 * convierten a texto con el formato visible de la celda. Las fórmulas no se evalúan.
 */
class XlsxTableReader implements TableReader {

    private final DataFormatter formatter = new DataFormatter();

    @Override
    public Table read(byte[] content) {
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheetAt(0);
            List<String> headers = null;
            List<Row> rows = new ArrayList<>();
            for (org.apache.poi.ss.usermodel.Row row : sheet) {
                List<String> cells = new ArrayList<>();
                int last = Math.max(row.getLastCellNum(), headers == null ? 0 : headers.size());
                for (int i = 0; i < last; i++) {
                    Cell cell = row.getCell(i);
                    cells.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
                }
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
                rows.add(new Row(row.getRowNum() + 1, cells));
            }
            if (headers == null) {
                throw DomainException.badRequest("La hoja está vacía");
            }
            return new Table(headers, rows);
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof DomainException de) {
                throw de;
            }
            throw DomainException.badRequest("El archivo Excel no es válido");
        }
    }
}
