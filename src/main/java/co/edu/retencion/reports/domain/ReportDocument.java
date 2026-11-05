package co.edu.retencion.reports.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Contenido de un reporte, independiente del formato. Las filas no están en memoria: cada sección las
 * entrega por {@link RowSource}, de a una, para que un reporte de cientos de miles de filas no agote
 * el heap.
 *
 * <p><b>Patrón Builder:</b> un reporte tiene título, aviso, marca de agua y un número variable de
 * secciones; {@link Builder#build()} exige lo obligatorio, así no existe un reporte sin aviso de
 * confidencialidad.
 */
public final class ReportDocument {

    /** Entrega las filas una por una; una celda {@code null} queda vacía. */
    @FunctionalInterface
    public interface RowSource {
        void forEachRow(Consumer<List<Object>> sink);
    }

    /** Tabla del reporte. */
    public record Section(String title, List<String> columns, RowSource rows) {
    }

    private final String title;
    private final String notice;
    private final String watermark;
    private final List<Section> sections;

    private ReportDocument(Builder b) {
        this.title = b.title;
        this.notice = b.notice;
        this.watermark = b.watermark;
        this.sections = List.copyOf(b.sections);
    }

    public String title() {
        return title;
    }

    public String notice() {
        return notice;
    }

    public String watermark() {
        return watermark;
    }

    public List<Section> sections() {
        return sections;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Arma el reporte por partes. */
    public static final class Builder {

        private String title;
        private String notice;
        private String watermark = "CONFIDENCIAL";
        private final List<Section> sections = new ArrayList<>();

        public Builder title(String value) {
            this.title = value;
            return this;
        }

        public Builder notice(String value) {
            this.notice = value;
            return this;
        }

        public Builder watermark(String value) {
            this.watermark = value;
            return this;
        }

        public Builder section(String sectionTitle, List<String> columns, RowSource rows) {
            sections.add(new Section(sectionTitle, List.copyOf(columns), rows));
            return this;
        }

        public ReportDocument build() {
            if (title == null || title.isBlank()) {
                throw new IllegalStateException("El reporte necesita título");
            }
            if (notice == null || notice.isBlank()) {
                throw new IllegalStateException("El reporte necesita aviso de confidencialidad");
            }
            if (sections.isEmpty()) {
                throw new IllegalStateException("El reporte necesita al menos una sección");
            }
            return new ReportDocument(this);
        }
    }
}
