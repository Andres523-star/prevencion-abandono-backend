package co.edu.retencion.reports.infrastructure;

import org.springframework.stereotype.Component;

import co.edu.retencion.reports.domain.ReportJob;
import co.edu.retencion.reports.domain.ReportWriter;

/**
 * <b>Patrón Factory Method:</b> decide qué escritor construir según el formato pedido; agregar un
 * formato no toca al trabajador de reportes.
 */
@Component
public class ReportWriterFactory {

    public ReportWriter forFormat(ReportJob.Format format) {
        return switch (format) {
            case PDF -> new PdfReportWriter();
            case XLSX -> new XlsxReportWriter();
        };
    }
}
