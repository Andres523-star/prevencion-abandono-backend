package co.edu.retencion.reports.infrastructure;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import co.edu.retencion.reports.domain.ReportDocument;

/** Se ejecuta en una JVM aparte con poco heap: ver {@link ReportWritersTest}. */
public final class LargeExcelMain {

    private LargeExcelMain() {
    }

    public static void main(String[] args) throws IOException {
        int rows = Integer.parseInt(args[1]);
        ReportDocument document = ReportDocument.builder().title("Volumen").notice("Uso interno")
                .section("Estudiantes", List.of("Codigo", "Nombre", "Programa", "Semestre", "Nivel", "Puntaje"), sink -> {
                    for (int i = 0; i < rows; i++) {
                        sink.accept(Arrays.asList("EST-" + i, "Nombre de prueba " + i, "ISW", i % 10 + 1, "ALTO", 60 + i % 40));
                    }
                }).build();
        long written = new XlsxReportWriter().write(document, Path.of(args[0]), Long.MAX_VALUE);
        if (written != rows) {
            throw new IllegalStateException("Filas escritas: " + written);
        }
    }
}
