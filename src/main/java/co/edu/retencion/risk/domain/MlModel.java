package co.edu.retencion.risk.domain;

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

import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.id.UuidV7;

/**
 * Versión registrada del modelo predictivo propio (artefacto ONNX + ficha con variables, periodos
 * de entrenamiento y prueba y métricas). Para usarse debe estar APROBADO (tras compararlo con el
 * activo) y su versión debe estar en {@code risk.ml.active-version}; volver atrás = poner la versión
 * anterior en ese parámetro.
 */
@Entity
@Table(name = "ml_models")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MlModel {

    /** Estado del modelo. */
    public enum Status {
        REGISTRADO,
        APROBADO,
        RETIRADO
    }

    @Id
    private UUID id;

    @Column(name = "model_version", nullable = false, unique = true)
    private String modelVersion;

    @Column(nullable = false)
    private String algorithm;

    @Column(name = "trained_at", nullable = false)
    private Instant trainedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "training_terms", nullable = false, columnDefinition = "jsonb")
    private String trainingTerms;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "test_terms", nullable = false, columnDefinition = "jsonb")
    private String testTerms;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String features;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String metrics;

    @Column(name = "output_name", nullable = false)
    private String outputName;

    @Column(name = "artifact_key", nullable = false)
    private String artifactKey;

    @Column(name = "artifact_sha256", nullable = false)
    private String artifactSha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String comparison;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "registered_by", nullable = false)
    private UUID registeredBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(name = "lock_version")
    private long lockVersion;

    /** Ficha del modelo (sin el binario). */
    public record Card(String modelVersion, String algorithm, Instant trainedAt, String trainingTermsJson,
                       String testTermsJson, String featuresJson, String metricsJson, String outputName) {
    }

    public static MlModel register(Card card, String artifactKey, String sha256, UUID registeredBy, Instant now) {
        MlModel m = new MlModel();
        m.id = UuidV7.newId();
        m.modelVersion = card.modelVersion();
        m.algorithm = card.algorithm();
        m.trainedAt = card.trainedAt();
        m.trainingTerms = card.trainingTermsJson();
        m.testTerms = card.testTermsJson();
        m.features = card.featuresJson();
        m.metrics = card.metricsJson();
        m.outputName = card.outputName();
        m.artifactKey = artifactKey;
        m.artifactSha256 = sha256;
        m.status = Status.REGISTRADO;
        m.registeredBy = registeredBy;
        m.createdAt = now;
        return m;
    }

    public void approve(String comparisonJson, UUID approver, Instant now) {
        if (status != Status.REGISTRADO) {
            throw DomainException.conflict("Solo se aprueba un modelo REGISTRADO");
        }
        this.status = Status.APROBADO;
        this.comparison = comparisonJson;
        this.approvedBy = approver;
        this.approvedAt = now;
    }

    public void retire() {
        this.status = Status.RETIRADO;
    }
}
