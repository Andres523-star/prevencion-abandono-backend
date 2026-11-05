package co.edu.retencion.reports.domain;

import java.io.IOException;
import java.nio.file.Path;

/** Puerto de escritura de un reporte a un archivo. */
public interface ReportWriter {

    ReportJob.Format format();

    /**
     * Escribe el reporte sin cargarlo en memoria.
     *
     * @param maxRows máximo de filas de datos, si el formato lo necesita
     * @return filas de datos escritas
     * @throws ReportTooLargeException si supera {@code maxRows}
     */
    long write(ReportDocument document, Path target, long maxRows) throws IOException;
}
