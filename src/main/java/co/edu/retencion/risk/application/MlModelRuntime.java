package co.edu.retencion.risk.application;

import java.util.Optional;

import co.edu.retencion.risk.domain.MlModel;
import co.edu.retencion.risk.domain.MlScorer.ProbabilityModel;

/** Puerto hacia el runtime de modelos (ONNX Runtime en infraestructura). */
public interface MlModelRuntime {

    /** Modelo listo para predecir, o vacío si el artefacto no se pudo cargar o no coincide su hash. */
    Optional<ProbabilityModel> load(MlModel model);

    /** Valida un artefacto antes de registrarlo; devuelve los errores (vacío = cargable). */
    java.util.List<String> verify(byte[] onnx, String outputName, int featureCount);
}
