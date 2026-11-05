package co.edu.retencion.reports.domain;

/** El reporte supera el máximo de filas de su formato (un PDF enorme no sirve; se pide en Excel). */
public class ReportTooLargeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ReportTooLargeException(long maxRows) {
        super("El reporte supera las " + maxRows + " filas");
    }
}
