package co.edu.retencion.imports.domain;

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

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import co.edu.retencion.common.id.UuidV7;

/** Trabajo de importación: tipo, archivo, modo, estado y contadores por resultado. */
@Entity
@Table(name = "import_jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImportJob {

    /** Qué se importa. */
    public enum Type {
        STUDENTS,
        TEACHERS,
        SUBJECTS,
        GROUPS,
        ENROLLMENTS,
        GRADES,
        DROPOUT_HISTORY
    }

    /** Formato del archivo. */
    public enum Format {
        CSV,
        XLSX
    }

    /** Estado del trabajo. */
    public enum Status {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED
    }

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "import_type", nullable = false)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_format", nullable = false)
    private Format format;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "file_sha256", nullable = false)
    private String fileSha256;

    @Column(name = "dry_run", nullable = false)
    private boolean dryRun;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "column_mapping")
    private String columnMapping;

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(name = "created_rows", nullable = false)
    private int createdRows;

    @Column(name = "updated_rows", nullable = false)
    private int updatedRows;

    @Column(name = "unchanged_rows", nullable = false)
    private int unchangedRows;

    @Column(name = "valid_rows", nullable = false)
    private int validRows;

    @Column(name = "error_rows", nullable = false)
    private int errorRows;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "requested_by")
    private UUID requestedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Version
    private long version;

    public static ImportJob submit(Type type, Format format, String fileName, String sha256, boolean dryRun,
                                   String columnMapping, UUID requestedBy, Instant now) {
        ImportJob job = new ImportJob();
        job.id = UuidV7.newId();
        job.type = type;
        job.format = format;
        job.fileName = fileName;
        job.fileSha256 = sha256;
        job.dryRun = dryRun;
        job.columnMapping = columnMapping;
        job.status = Status.PENDING;
        job.requestedBy = requestedBy;
        job.createdAt = now;
        return job;
    }

    public void start(Instant now) {
        this.status = Status.PROCESSING;
        this.startedAt = now;
    }

    /**
     * Cierra el trabajo con los contadores finales.
     *
     * @param counts created, updated, unchanged, valid, error (en ese orden)
     */
    public void complete(int total, int[] counts, Instant now) {
        this.totalRows = total;
        this.createdRows = counts[0];
        this.updatedRows = counts[1];
        this.unchangedRows = counts[2];
        this.validRows = counts[3];
        this.errorRows = counts[4];
        this.status = Status.COMPLETED;
        this.finishedAt = now;
    }

    public void fail(String reason, Instant now) {
        this.status = Status.FAILED;
        this.failureReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 500));
        this.finishedAt = now;
    }

    public boolean isFinished() {
        return status == Status.COMPLETED || status == Status.FAILED;
    }
}
