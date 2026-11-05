package co.edu.retencion.reports.infrastructure;

import java.awt.Color;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfGState;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;

import co.edu.retencion.reports.domain.ReportDocument;
import co.edu.retencion.reports.domain.ReportJob;
import co.edu.retencion.reports.domain.ReportTooLargeException;
import co.edu.retencion.reports.domain.ReportWriter;

/**
 * PDF con marca de agua diagonal y aviso en cada página. Las tablas se vuelcan cada {@value #CHUNK}
 * filas para no acumular el documento en memoria.
 *
 * <p><b>Patrón Adapter:</b> adapta OpenPDF al puerto {@link ReportWriter}.
 */
public class PdfReportWriter implements ReportWriter {

    private static final int CHUNK = 200;
    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
    private static final Font SECTION = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);
    private static final Font HEAD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);
    private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 8);
    private static final Font SMALL = FontFactory.getFont(FontFactory.HELVETICA, 7, Font.NORMAL, Color.DARK_GRAY);

    @Override
    public ReportJob.Format format() {
        return ReportJob.Format.PDF;
    }

    @Override
    public long write(ReportDocument report, Path target, long maxRows) throws IOException {
        Document document = new Document(PageSize.A4.rotate(), 36, 36, 48, 40);
        long[] total = {0};
        try (OutputStream out = Files.newOutputStream(target)) {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new Confidential(report));
            document.open();
            document.addTitle(report.title());
            document.add(new Paragraph(report.title(), TITLE));
            for (ReportDocument.Section section : report.sections()) {
                writeSection(document, section, total, maxRows);
            }
            document.close();
        } catch (DocumentException ex) {
            throw new IOException("No se pudo escribir el PDF", ex);
        }
        return total[0];
    }

    private static void writeSection(Document document, ReportDocument.Section section, long[] total, long maxRows) {
        Paragraph title = new Paragraph(section.title(), SECTION);
        title.setSpacingBefore(12);
        title.setSpacingAfter(6);
        document.add(title);
        PdfPTable[] table = {newTable(section)};
        int[] pending = {0};
        section.rows().forEachRow(values -> {
            if (++total[0] > maxRows) {
                throw new ReportTooLargeException(maxRows);
            }
            for (Object value : values) {
                PdfPCell cell = new PdfPCell(new Phrase(value == null ? "" : value.toString(), BODY));
                cell.setBorderColor(Color.LIGHT_GRAY);
                table[0].addCell(cell);
            }
            if (++pending[0] == CHUNK) {
                document.add(table[0]);
                table[0] = newTable(section);
                pending[0] = 0;
            }
        });
        if (pending[0] > 0 || total[0] == 0) {
            document.add(table[0]);
        }
    }

    private static PdfPTable newTable(ReportDocument.Section section) {
        PdfPTable table = new PdfPTable(section.columns().size());
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (String column : section.columns()) {
            PdfPCell cell = new PdfPCell(new Phrase(column, HEAD));
            cell.setBackgroundColor(new Color(230, 230, 230));
            table.addCell(cell);
        }
        return table;
    }

    /** Aviso, pie y marca de agua de cada página. */
    private static final class Confidential extends PdfPageEventHelper {

        private final ReportDocument report;

        Confidential(ReportDocument report) {
            this.report = report;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            float width = document.getPageSize().getWidth();
            float height = document.getPageSize().getHeight();
            PdfContentByte under = writer.getDirectContentUnder();
            under.saveState();
            PdfGState light = new PdfGState();
            light.setFillOpacity(0.12f);
            under.setGState(light);
            ColumnText.showTextAligned(under, Element.ALIGN_CENTER,
                    new Phrase(report.watermark(), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 72, Color.GRAY)),
                    width / 2, height / 2, 35);
            under.restoreState();
            PdfContentByte over = writer.getDirectContent();
            ColumnText.showTextAligned(over, Element.ALIGN_CENTER, new Phrase(report.watermark(), HEAD), width / 2,
                    height - 28, 0);
            ColumnText.showTextAligned(over, Element.ALIGN_LEFT, new Phrase(report.notice(), SMALL), 36, 22, 0);
            ColumnText.showTextAligned(over, Element.ALIGN_RIGHT, new Phrase("Página " + writer.getPageNumber(), SMALL),
                    width - 36, 22, 0);
        }
    }
}
