package co.edu.retencion.risk.infrastructure;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import ai.onnxruntime.OnnxMap;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import co.edu.retencion.common.files.ObjectStorage;
import co.edu.retencion.risk.application.MlModelRuntime;
import co.edu.retencion.risk.domain.MlModel;
import co.edu.retencion.risk.domain.MlScorer.ProbabilityModel;

/**
 * <b>Patrón Adapter:</b> adapta ONNX Runtime para Java al puerto {@link MlModelRuntime} y al
 * {@link ProbabilityModel} del dominio. Carga el artefacto desde el almacenamiento de objetos,
 * verifica su SHA-256 contra el registro (un artefacto alterado no se usa) y mantiene una sesión por
 * versión en memoria.
 *
 * <p>Formatos de salida admitidos: tensor de probabilidades {@code [N,2]} (clase positiva = última
 * columna) o {@code [N,1]}, y la secuencia de mapas {@code ZipMap} que generan skl2onnx/onnxmltools.
 */
@Component
public class OnnxModelRuntime implements MlModelRuntime {

    private static final Logger log = LoggerFactory.getLogger(OnnxModelRuntime.class);

    private final ObjectStorage storage;
    private final ObjectMapper mapper;
    private final Map<String, OnnxProbabilityModel> sessions = new ConcurrentHashMap<>();

    public OnnxModelRuntime(ObjectStorage storage, ObjectMapper mapper) {
        this.storage = storage;
        this.mapper = mapper;
    }

    @Override
    public Optional<ProbabilityModel> load(MlModel model) {
        String cacheKey = model.getModelVersion() + "@" + model.getArtifactSha256();
        OnnxProbabilityModel cached = sessions.get(cacheKey);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            byte[] bytes = storage.get(model.getArtifactKey());
            if (!sha256(bytes).equals(model.getArtifactSha256())) {
                log.error("El artefacto del modelo {} no coincide con su hash registrado", model.getModelVersion());
                return Optional.empty();
            }
            List<String> features = new ArrayList<>();
            mapper.readTree(model.getFeatures()).forEach(n -> features.add(n.asText()));
            OnnxProbabilityModel loaded = new OnnxProbabilityModel(model.getModelVersion(), features,
                    OrtEnvironment.getEnvironment().createSession(bytes, new OrtSession.SessionOptions()),
                    model.getOutputName());
            sessions.put(cacheKey, loaded);
            return Optional.of(loaded);
        } catch (OrtException | JsonProcessingException | RuntimeException ex) {
            log.error("No se pudo cargar el modelo {}: {}", model.getModelVersion(), ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public List<String> verify(byte[] onnx, String outputName, int featureCount) {
        List<String> errors = new ArrayList<>();
        try (OrtSession session = OrtEnvironment.getEnvironment().createSession(onnx, new OrtSession.SessionOptions())) {
            if (session.getInputNames().size() != 1) {
                errors.add("El modelo debe tener exactamente una entrada");
                return errors;
            }
            if (outputName == null || !session.getOutputNames().contains(outputName)) {
                errors.add("El modelo no tiene la salida declarada: " + outputName);
                return errors;
            }
            double p = new OnnxProbabilityModel("verificacion", List.of(), session, outputName)
                    .run(new float[featureCount]);
            if (Double.isNaN(p) || p < 0 || p > 1) {
                errors.add("La salida del modelo no es una probabilidad entre 0 y 1");
            }
        } catch (OrtException | RuntimeException ex) {
            errors.add("ONNX Runtime no pudo cargar o ejecutar el modelo (" + ex.getClass().getSimpleName() + ")");
        }
        return errors;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Modelo ONNX cargado. Las sesiones de ONNX Runtime son seguras para uso concurrente. */
    static final class OnnxProbabilityModel implements ProbabilityModel {

        private final String version;
        private final List<String> features;
        private final OrtSession session;
        private final String outputName;
        private final String inputName;

        OnnxProbabilityModel(String version, List<String> features, OrtSession session, String outputName) {
            this.version = version;
            this.features = List.copyOf(features);
            this.session = session;
            this.outputName = outputName;
            this.inputName = session.getInputNames().iterator().next();
        }

        @Override
        public String version() {
            return version;
        }

        @Override
        public List<String> features() {
            return features;
        }

        @Override
        public double probability(float[] vector) {
            try {
                return run(vector);
            } catch (OrtException ex) {
                throw new IllegalStateException("Fallo de inferencia del modelo " + version, ex);
            }
        }

        double run(float[] vector) throws OrtException {
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            try (OnnxTensor input = OnnxTensor.createTensor(env, new float[][] {vector});
                 OrtSession.Result result = session.run(Map.of(inputName, input))) {
                OnnxValue value = result.get(outputName).orElseThrow(() -> new IllegalStateException("Sin salida"));
                return positive(value.getValue());
            }
        }

        private static double positive(Object raw) throws OrtException {
            if (raw instanceof float[][] f) {
                return f[0][f[0].length - 1];
            }
            if (raw instanceof double[][] d) {
                return d[0][d[0].length - 1];
            }
            if (raw instanceof float[] f) {
                return f[0];
            }
            if (raw instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof OnnxMap map) {
                Map<?, ?> m = map.getValue();
                Object v = m.containsKey(1L) ? m.get(1L) : m.get("1");
                return v instanceof Number n ? n.doubleValue() : Double.NaN;
            }
            throw new IllegalStateException("Formato de salida ONNX no soportado");
        }
    }
}
