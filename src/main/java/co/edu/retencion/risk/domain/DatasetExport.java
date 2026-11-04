package co.edu.retencion.risk.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import co.edu.retencion.common.id.UuidV7;

/** Registro de cada exportación del dataset de entrenamiento: quién, por qué, qué periodos, filas y hash. */
@Entity
@Table(name = "ml_dataset_exports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DatasetExport {

    @Id
    private UUID id;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(nullable = false)
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String terms;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Column(nullable = false)
    private String sha256;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static DatasetExport of(UUID requestedBy, String reason, String termsJson, int rows, String sha256,
                                   Instant now) {
        DatasetExport e = new DatasetExport();
        e.id = UuidV7.newId();
        e.requestedBy = requestedBy;
        e.reason = reason;
        e.terms = termsJson;
        e.rowCount = rows;
        e.sha256 = sha256;
        e.createdAt = now;
        return e;
    }
}
