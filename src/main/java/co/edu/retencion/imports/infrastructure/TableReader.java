package co.edu.retencion.imports.infrastructure;

import java.util.List;

import co.edu.retencion.imports.domain.ImportJob;

/**
 * Puerto de lectura de archivos tabulares. Las implementaciones son <b>adaptadores</b> sobre
 * Apache Commons CSV y Apache POI, y {@link #forFormat} es su <b>método de fabricación</b>.
 */
public interface TableReader {

    /** Tamaño máximo de filas por archivo (protección contra archivos desmesurados). */
    int MAX_ROWS = 200_000;

    Table read(byte[] content);

    /**
     * Contenido del archivo.
     *
     * @param headers encabezados (primera fila)
     * @param rows    filas de datos, en orden; las filas totalmente vacías se omiten
     */
    record Table(List<String> headers, List<Row> rows) {
    }

    /**
     * @param number número de fila en el archivo (1 = encabezado), para el reporte
     * @param cells  celdas como texto
     */
    record Row(int number, List<String> cells) {
    }

    static TableReader forFormat(ImportJob.Format format) {
        return switch (format) {
            case CSV -> new CsvTableReader();
            case XLSX -> new XlsxTableReader();
        };
    }
}
