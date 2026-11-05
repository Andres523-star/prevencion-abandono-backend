package co.edu.retencion.risk.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import co.edu.retencion.common.files.ObjectStorage;
import co.edu.retencion.risk.domain.MlModel;
import co.edu.retencion.risk.domain.MlScorer.ProbabilityModel;
import co.edu.retencion.support.OnnxTestModels;

/** ONNX Runtime real con un modelo logístico construido en la prueba. */
class OnnxModelRuntimeTest {

    static final class MemoryStorage implements ObjectStorage {
        final Map<String, byte[]> objects = new HashMap<>();

        @Override
        public void put(String key, byte[] content, String mediaType) {
            objects.put(key, content);
        }

        @Override
        public void put(String key, java.nio.file.Path file, String mediaType) {
            try {
                objects.put(key, java.nio.file.Files.readAllBytes(file));
            } catch (java.io.IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        }

        @Override
        public byte[] get(String key) {
            return objects.get(key);
        }

        @Override
        public void delete(String key) {
            objects.remove(key);
        }

        @Override
        public URI presignedDownloadUrl(String key, Duration ttl, String downloadFileName) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Modelo generado por {@code ml/train.py} (LightGBM + calibración isotónica, 15 árboles, datos
     * sintéticos): verifica que el formato real de entrenamiento (TreeEnsembleClassifier, salida
     * {@code probabilities [N,2]}) carga y predice en ONNX Runtime para Java.
     */
    @Test
    void loadsTheLightGbmModelProducedByTheTrainingScript() throws Exception {
        byte[] onnx;
        try (var in = getClass().getResourceAsStream("/ml/lgbm-sintetico.onnx")) {
            onnx = in.readAllBytes();
        }
        OnnxModelRuntime runtime = new OnnxModelRuntime(new MemoryStorage(), new ObjectMapper());
        assertThat(runtime.verify(onnx, "probabilities", co.edu.retencion.risk.domain.MlFeatureVector.FEATURES.size()))
                .isEmpty();
        MemoryStorage storage = new MemoryStorage();
        storage.put("modelos/lgbm-sintetico-onnx", onnx, "application/octet-stream");
        MlModel model = MlModel.register(new MlModel.Card("lgbm-sintetico", "lightgbm+isotonic", Instant.now(), "[]",
                "[]", new ObjectMapper().writeValueAsString(co.edu.retencion.risk.domain.MlFeatureVector.FEATURES), "{}",
                "probabilities"), "modelos/lgbm-sintetico-onnx", OnnxModelRuntime.sha256(onnx), UUID.randomUUID(),
                Instant.now());
        ProbabilityModel loaded = new OnnxModelRuntime(storage, new ObjectMapper()).load(model).orElseThrow();
        float[] risky = new float[14];
        risky[0] = 3;
        risky[1] = 2.2f;
        risky[2] = 2;
        risky[3] = 5;
        risky[4] = 0.75f;
        // Mismo valor que da onnxruntime en Python para este vector.
        assertThat(loaded.probability(risky)).isCloseTo(0.8888889, within(1e-5));
        assertThat(loaded.probability(new float[14])).isBetween(0.0, 1.0);
    }

    @Test
    void loadsVerifiedModelsAndRejectsTamperedOrBrokenOnes() {
        byte[] onnx = OnnxTestModels.logistic(new float[] {2.0f, 1.5f}, -1.0f, "probability");
        MemoryStorage storage = new MemoryStorage();
        OnnxModelRuntime runtime = new OnnxModelRuntime(storage, new ObjectMapper());

        assertThat(runtime.verify(onnx, "probability", 2)).isEmpty();
        assertThat(runtime.verify(onnx, "otra_salida", 2)).singleElement()
                .satisfies(e -> assertThat(e).contains("salida declarada"));
        assertThat(runtime.verify(new byte[] {1, 2, 3}, "probability", 2)).isNotEmpty();

        storage.put("modelos/m.onnx", onnx, "application/octet-stream");
        MlModel model = MlModel.register(new MlModel.Card("logit-1", "logistic", Instant.now(), "[]", "[]",
                "[\"max_absence_ratio\",\"subjects_below_passing\"]", "{}", "probability"), "modelos/m.onnx",
                OnnxModelRuntime.sha256(onnx), UUID.randomUUID(), Instant.now());
        ProbabilityModel loaded = runtime.load(model).orElseThrow();
        assertThat(loaded.features()).containsExactly("max_absence_ratio", "subjects_below_passing");
        double expected = 1 / (1 + Math.exp(-(2.0 * 0.75 + 1.5 * 1 - 1.0)));
        assertThat(loaded.probability(new float[] {0.75f, 1f})).isCloseTo(expected, within(1e-5));
        // Segunda carga: misma sesión en caché.
        assertThat(runtime.load(model)).containsSame(loaded);

        MlModel tampered = MlModel.register(new MlModel.Card("logit-2", "logistic", Instant.now(), "[]", "[]",
                "[\"semester\"]", "{}", "probability"), "modelos/m.onnx", "0".repeat(64), UUID.randomUUID(),
                Instant.now());
        assertThat(runtime.load(tampered)).isEmpty();
        assertThat(List.of(onnx.length)).allMatch(n -> n > 50);
    }
}
