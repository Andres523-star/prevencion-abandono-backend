package co.edu.retencion.risk.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.files.ObjectStorage;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.risk.domain.MlFeatureVector;
import co.edu.retencion.risk.domain.MlModel;
import co.edu.retencion.risk.domain.MlModelRepository;
import co.edu.retencion.risk.domain.ModelComparison;
import co.edu.retencion.settings.SettingsProvider;

/**
 * Registro de versiones del modelo predictivo propio. Flujo: registrar (ficha + ONNX, se valida
 * que cargue y que sus variables cumplan la política de sesgo) → comparar con el modelo activo →
 * aprobar (bloqueado si es peor o tiene brechas entre grupos, salvo anulación explícita con motivo)
 * → activar poniendo la versión en {@code risk.ml.active-version}. Volver atrás = poner la versión
 * anterior en ese parámetro.
 */
@Service
public class MlModelRegistryService {

    static final String ACTIVE_KEY = "risk.ml.active-version";

    private final MlModelRepository models;
    private final MlModelRuntime runtime;
    private final ObjectStorage storage;
    private final SettingsProvider settings;
    private final ObjectMapper mapper;
    private final Clock clock;

    public MlModelRegistryService(MlModelRepository models, MlModelRuntime runtime, ObjectStorage storage,
                                  SettingsProvider settings, ObjectMapper mapper, Clock clock) {
        this.models = models;
        this.runtime = runtime;
        this.storage = storage;
        this.settings = settings;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * Ficha del modelo (la produce el script de entrenamiento, {@code ml/train.py}).
     *
     * @param version       versión, p. ej. {@code lgbm-2026-11-04}
     * @param algorithm     algoritmo
     * @param trainedAt     fecha de entrenamiento
     * @param trainingTerms periodos de entrenamiento
     * @param testTerms     periodos de prueba (validación temporal: posteriores a los de entrenamiento)
     * @param features      variables en el orden del tensor de entrada
     * @param metrics       métricas (auc, recallHighRisk, brier, ece, byProgram, byGroup, calibration)
     * @param outputName    salida del grafo ONNX con la probabilidad
     */
    public record ModelCard(String version, String algorithm, Instant trainedAt, List<String> trainingTerms,
                            List<String> testTerms, List<String> features, JsonNode metrics, String outputName) {
    }

    public record ModelView(UUID id, String version, String algorithm, Instant trainedAt, List<String> trainingTerms,
                            List<String> testTerms, List<String> features, JsonNode metrics, MlModel.Status status,
                            JsonNode comparison, boolean active, String artifactSha256, Instant createdAt,
                            Instant approvedAt) {
    }

    public record ComparisonView(String candidate, String against, ModelComparison.Verdict verdict,
                                 Map<String, Double> deltas, List<String> blockers, List<String> warnings,
                                 boolean approvable) {
    }

    @Transactional
    public ModelView register(ModelCard card, byte[] onnx) {
        List<String> errors = new ArrayList<>(MlFeatureVector.validate(card.features()));
        if (card.version() == null || !card.version().matches("^[a-z0-9]+([._-][a-z0-9]+)*$")) {
            errors.add("Versión inválida (minúsculas, números, punto, guion)");
        }
        if (card.metrics() == null || !card.metrics().isObject()) {
            errors.add("La ficha no trae métricas");
        }
        if (card.trainingTerms() == null || card.trainingTerms().isEmpty() || card.testTerms() == null
                || card.testTerms().isEmpty()) {
            errors.add("La ficha debe declarar periodos de entrenamiento y de prueba (validación temporal)");
        } else if (card.trainingTerms().stream().anyMatch(card.testTerms()::contains)) {
            errors.add("Los periodos de prueba no pueden estar en el entrenamiento");
        }
        if (onnx == null || onnx.length == 0) {
            errors.add("Falta el artefacto ONNX");
        } else if (errors.isEmpty()) {
            errors.addAll(runtime.verify(onnx, card.outputName(), card.features().size()));
        }
        if (!errors.isEmpty()) {
            throw new DomainException(RiskProblems.MODEL_REJECTED, String.join("; ", errors));
        }
        if (models.existsByModelVersion(card.version())) {
            throw DomainException.conflict("Ya existe un modelo con esa versión");
        }
        // Las claves de objeto solo admiten letras, números, "/", "_" y "-" (sin puntos).
        String key = "modelos/" + card.version().replace('.', '_') + "-onnx";
        storage.put(key, onnx, "application/octet-stream");
        MlModel model = MlModel.register(new MlModel.Card(card.version(), card.algorithm(),
                card.trainedAt() == null ? clock.instant() : card.trainedAt(), json(card.trainingTerms()),
                json(card.testTerms()), json(card.features()), json(card.metrics()), card.outputName()), key,
                RiskJson.sha256(onnx), CurrentUser.require().userId(), clock.instant());
        return view(models.save(model));
    }

    @Transactional(readOnly = true)
    public List<ModelView> list() {
        return models.findAllByOrderByCreatedAtDesc().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public ComparisonView compare(String version) {
        MlModel candidate = load(version);
        String activeVersion = settings.getString(ACTIVE_KEY, "").trim();
        JsonNode current = activeVersion.isEmpty() || activeVersion.equals(version) ? null
                : models.findByModelVersion(activeVersion).map(m -> read(m.getMetrics())).orElse(null);
        ModelComparison.Result r = ModelComparison.compare(read(candidate.getMetrics()), current);
        return new ComparisonView(version, current == null ? null : activeVersion, r.verdict(), r.deltas(), r.blockers(),
                r.warnings(), r.approvable());
    }

    /** Aprueba tras comparar; con bloqueos solo si {@code override} y un motivo (queda en la auditoría). */
    @Transactional
    public ModelView approve(String version, boolean override, String reason) {
        MlModel model = load(version);
        ComparisonView comparison = compare(version);
        if (!comparison.approvable() && (!override || reason == null || reason.isBlank())) {
            throw new DomainException(RiskProblems.MODEL_NOT_APPROVABLE, String.join("; ", comparison.blockers()));
        }
        Map<String, Object> record = new java.util.LinkedHashMap<>();
        record.put("comparison", comparison);
        record.put("override", !comparison.approvable());
        record.put("reason", reason);
        model.approve(json(record), CurrentUser.require().userId(), clock.instant());
        return view(models.saveAndFlush(model));
    }

    private MlModel load(String version) {
        return models.findByModelVersion(version).orElseThrow(() -> DomainException.notFound("Modelo no encontrado"));
    }

    private ModelView view(MlModel m) {
        String active = settings.getString(ACTIVE_KEY, "").trim();
        return new ModelView(m.getId(), m.getModelVersion(), m.getAlgorithm(), m.getTrainedAt(),
                list(m.getTrainingTerms()), list(m.getTestTerms()), list(m.getFeatures()), read(m.getMetrics()),
                m.getStatus(), m.getComparison() == null ? null : read(m.getComparison()),
                active.equals(m.getModelVersion()) && m.getStatus() == MlModel.Status.APROBADO, m.getArtifactSha256(),
                m.getCreatedAt(), m.getApprovedAt());
    }

    private List<String> list(String json) {
        List<String> out = new ArrayList<>();
        read(json).forEach(n -> out.add(n.asText()));
        return out;
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
