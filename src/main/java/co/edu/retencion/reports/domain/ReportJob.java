package co.edu.retencion.reports.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import co.edu.retencion.common.id.UuidV7;

/**
 * Trabajo de generación de un reporte. Guarda los filtros y el alcance de quien lo pidió: se genera en
 * segundo plano, sin petición, y no debe ver más de lo que esa persona veía al pedirlo.
 *
 * <p>Ciclo: PENDIENTE → PROCESANDO → LISTO → EXPIRADO, o FALLIDO.
 */
@Entity
@Table(name = "report_jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportJob {

    /** Contenido. */
    public enum Type {
        GENERAL,
        BY_PROGRAM,
        BY_SEMESTER,
        BY_YEAR,
        AT_RISK_STUDENTS,
        ATTENDANCE;

        /** Solo estos listan estudiantes; los demás son agregados. */
        public boolean listsStudents() {
            return this == AT_RISK_STUDENTS || this == ATTENDANCE;
        }
    }

    /** Formato del archivo. */
    public enum Format {
        PDF("application/pdf", "pdf"),
        XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");

        private final String mediaType;
        private final String extension;

        Format(String mediaType, String extension) {
            this.mediaType = mediaType;
            this.extension = extension;
        }

        public String mediaType() {
            return mediaType;
        }

        public String extension() {
            return extension;
        }
    }

    /** Estado. */
    public enum Status {
        PENDIENTE,
        PROCESANDO,
        LISTO,
        FALLIDO,
        EXPIRADO
    }

    @Id
    private UUID id;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_type", nullable = false)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Format format;

    @Column(nullable = false)
    private String filters;

    @Column(name = "scope_programs")
    private String scopePrograms;

    @Column(name = "include_identity", nullable = false)
    private boolean includeIdentity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "object_key")
    private String objectKey;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "media_type")
    private String mediaType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "row_count")
    private Long rowCount;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Version
    private long version;

    /**
     * @param filters       filtros en JSON
     * @param scopePrograms programas visibles en JSON; {@code null} = todos
     */
    public static ReportJob request(UUID requestedBy, Type type, Format format, String filters, String scopePrograms,
                                    boolean includeIdentity, Instant now) {
        if (includeIdentity && !type.listsStudents()) {
            throw new IllegalArgumentException("Este reporte es agregado: no incluye identidad");
        }
        ReportJob job = new ReportJob();
        job.id = UuidV7.newId();
        job.requestedBy = requestedBy;
        job.type = type;
        job.format = format;
        job.filters = filters;
        job.scopePrograms = scopePrograms;
        job.includeIdentity = includeIdentity;
        job.status = Status.PENDIENTE;
        job.createdAt = now;
        return job;
    }

    public void ready(String key, String name, long size, long rows, Duration life, Instant now) {
        status = Status.LISTO;
        objectKey = key;
        fileName = name;
        mediaType = format.mediaType();
        sizeBytes = size;
        rowCount = rows;
        finishedAt = now;
        expiresAt = now.plus(life);
        leaseUntil = null;
    }

    public void fail(String code, Instant now) {
        status = Status.FALLIDO;
        failureCode = code;
        finishedAt = now;
        leaseUntil = null;
    }

    public void expire() {
        status = Status.EXPIRADO;
        objectKey = null;
    }

    /** Listo y sin vencer: se puede descargar. */
    public boolean downloadable(Instant now) {
        return status == Status.LISTO && expiresAt != null && expiresAt.isAfter(now);
    }
}
