package co.edu.retencion.reports.infrastructure;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import co.edu.retencion.reports.domain.ReportDocument;
import co.edu.retencion.reports.domain.ReportJob;
import co.edu.retencion.reports.domain.ReportWriter;

/**
 * Excel en streaming: {@link SXSSFWorkbook} solo mantiene {@value #WINDOW} filas en memoria y baja el
 * resto a un temporal comprimido, así el tamaño del reporte no depende del heap.
 *
 * <p><b>Patrón Adapter:</b> adapta Apache POI al puerto {@link ReportWriter}.
 */
public class XlsxReportWriter implements ReportWriter {

    private static final int WINDOW = 100;

    @Override
    public ReportJob.Format format() {
        return ReportJob.Format.XLSX;
    }

    @Override
    public long write(ReportDocument document, Path target, long maxRows) throws IOException {
        long total = 0;
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(WINDOW)) {
            workbook.setCompressTempFiles(true);
            CellStyle bold = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            bold.setFont(font);
            int index = 0;
            for (ReportDocument.Section section : document.sections()) {
                index++;
                String name = WorkbookUtil.createSafeSheetName(index + " " + section.title());
                total += writeSheet(workbook.createSheet(name), document, section, bold);
            }
            try (OutputStream out = Files.newOutputStream(target)) {
                workbook.write(out);
            }
            workbook.dispose();
        }
        return total;
    }

    private static long writeSheet(SXSSFSheet sheet, ReportDocument document, ReportDocument.Section section,
                                   CellStyle bold) {
        // Aviso visible en la hoja y en cada página impresa.
        sheet.getHeader().setCenter(document.watermark());
        sheet.getFooter().setCenter(document.notice());
        int[] next = {0};
        text(sheet.createRow(next[0]++), document.watermark() + " - " + document.title(), bold);
        text(sheet.createRow(next[0]++), document.notice(), null);
        text(sheet.createRow(next[0]++), section.title(), bold);
        Row header = sheet.createRow(next[0]++);
        for (int i = 0; i < section.columns().size(); i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(section.columns().get(i));
            cell.setCellStyle(bold);
        }
        long[] rows = {0};
        section.rows().forEachRow(values -> {
            write(sheet.createRow(next[0]++), values);
            rows[0]++;
        });
        return rows[0];
    }

    private static void text(Row row, String value, CellStyle style) {
        Cell cell = row.createCell(0);
        cell.setCellValue(value);
        if (style != null) {
            cell.setCellStyle(style);
        }
    }

    /** Los textos van como texto, nunca como fórmula: un valor con "=" no se ejecuta al abrir el archivo. */
    private static void write(Row row, List<Object> values) {
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (value == null) {
                continue;
            }
            Cell cell = row.createCell(i);
            if (value instanceof BigDecimal number) {
                cell.setCellValue(number.doubleValue());
            } else if (value instanceof Number number) {
                cell.setCellValue(number.doubleValue());
            } else {
                cell.setCellValue(value.toString());
            }
        }
    }
}
