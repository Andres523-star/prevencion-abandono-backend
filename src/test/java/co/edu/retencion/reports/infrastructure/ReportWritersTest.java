package co.edu.retencion.reports.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import co.edu.retencion.reports.domain.ReportDocument;
import co.edu.retencion.reports.domain.ReportJob;
import co.edu.retencion.reports.domain.ReportTooLargeException;

class ReportWritersTest {

    @TempDir
    Path dir;

    private static ReportDocument document(int rows) {
        return ReportDocument.builder().title("Reporte de prueba").notice("Uso interno, Ley 1581")
                .section("Datos", List.of("Código", "Valor"), sink -> {
                    for (int i = 0; i < rows; i++) {
                        sink.accept(Arrays.asList("EST-" + i, i % 2 == 0 ? Integer.valueOf(i) : null));
                    }
                }).build();
    }

    @Test
    void factoryChoosesTheWriterByFormat() {
        ReportWriterFactory factory = new ReportWriterFactory();
        assertThat(factory.forFormat(ReportJob.Format.PDF)).isInstanceOf(PdfReportWriter.class);
        assertThat(factory.forFormat(ReportJob.Format.XLSX)).isInstanceOf(XlsxReportWriter.class);
        assertThat(factory.forFormat(ReportJob.Format.PDF).format()).isEqualTo(ReportJob.Format.PDF);
        assertThat(factory.forFormat(ReportJob.Format.XLSX).format()).isEqualTo(ReportJob.Format.XLSX);
    }

    @Test
    void excelCarriesTheNoticeAndKeepsFormulasAsText() throws IOException {
        ReportDocument doc = ReportDocument.builder().title("T").notice("Uso interno")
                .section("Datos", List.of("A"), sink -> sink.accept(List.of("=HYPERLINK(\"http://x\")"))).build();
        Path file = dir.resolve("r.xlsx");
        assertThat(new XlsxReportWriter().write(doc, file, Long.MAX_VALUE)).isEqualTo(1);
        try (XSSFWorkbook book = new XSSFWorkbook(Files.newInputStream(file))) {
            Sheet sheet = book.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).startsWith("CONFIDENCIAL");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Uso interno");
            assertThat(sheet.getHeader().getCenter()).isEqualTo("CONFIDENCIAL");
            assertThat(sheet.getRow(4).getCell(0).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
        }
    }

    @Test
    void pdfHasWatermarkOnEveryPageAndRespectsTheLimit() throws IOException {
        Path file = dir.resolve("r.pdf");
        assertThat(new PdfReportWriter().write(document(600), file, 1000)).isEqualTo(600);
        PdfReader reader = new PdfReader(Files.readAllBytes(file));
        try {
            assertThat(reader.getNumberOfPages()).isGreaterThan(1);
            PdfTextExtractor text = new PdfTextExtractor(reader);
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                assertThat(text.getTextFromPage(page)).contains("CONFIDENCIAL").contains("Uso interno, Ley 1581");
            }
            assertThat(text.getTextFromPage(1)).contains("Reporte de prueba").contains("EST-0");
        } finally {
            reader.close();
        }
        assertThatThrownBy(() -> new PdfReportWriter().write(document(11), dir.resolve("g.pdf"), 10))
                .isInstanceOf(ReportTooLargeException.class);
    }

    @Test
    void pdfWithoutRowsIsStillValid() throws IOException {
        Path file = dir.resolve("vacio.pdf");
        assertThat(new PdfReportWriter().write(document(0), file, 10)).isZero();
        PdfReader reader = new PdfReader(Files.readAllBytes(file));
        assertThat(reader.getNumberOfPages()).isEqualTo(1);
        reader.close();
    }

    /**
     * Criterio de aceptación: 100.000 filas sin agotar memoria. Corre en una JVM aparte con 48 MB de
     * heap; con un libro en memoria (XSSF) ese mismo archivo necesitaría cientos de MB.
     */
    @Test
    void hundredThousandRowsFitInASmallHeap() throws Exception {
        Path file = dir.resolve("grande.xlsx");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path log = dir.resolve("salida.log");
        // Archivo de argumentos: en Windows el classpath no cabe en la línea de comandos.
        Path arguments = dir.resolve("java.args");
        Files.writeString(arguments, "-cp \"" + classpath.replace('\\', '/') + "\"");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx48m",
                "-XX:+ExitOnOutOfMemoryError", "@" + arguments, LargeExcelMain.class.getName(), file.toString(), "100000")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertThat(process.waitFor(5, TimeUnit.MINUTES)).as("terminó a tiempo").isTrue();
        assertThat(process.exitValue()).as(Files.readString(log)).isZero();
        assertThat(rowsIn(file.toFile())).isEqualTo(100_000 + 4);
    }

    /** Cuenta las filas leyendo el XML en streaming, sin cargar el libro. */
    private static long rowsIn(File file) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry sheet = zip.getEntry("xl/worksheets/sheet1.xml");
            long rows = 0;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(zip.getInputStream(sheet),
                    StandardCharsets.UTF_8))) {
                char[] buffer = new char[1 << 16];
                String tail = "";
                int read;
                while ((read = reader.read(buffer)) > 0) {
                    String chunk = tail + new String(buffer, 0, read);
                    int at = -1;
                    while ((at = chunk.indexOf("<row ", at + 1)) >= 0) {
                        rows++;
                    }
                    int keep = Math.min(4, chunk.length());
                    tail = chunk.substring(chunk.length() - keep);
                }
            }
            return rows;
        }
    }
}
