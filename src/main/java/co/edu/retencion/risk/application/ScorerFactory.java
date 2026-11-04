package co.edu.retencion.risk.application;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import co.edu.retencion.risk.domain.MlModel;
import co.edu.retencion.risk.domain.MlModelRepository;
import co.edu.retencion.risk.domain.MlScorer;
import co.edu.retencion.risk.domain.RiskRules;
import co.edu.retencion.risk.domain.RiskScorer;
import co.edu.retencion.risk.domain.RulesScorer;

/**
 * <b>Patrón Factory Method:</b> decide qué {@link RiskScorer} construir para cada análisis según la
 * configuración vigente. Si {@code risk.ml.active-version} apunta a un modelo APROBADO y cargable,
 * crea un {@link MlScorer}; en cualquier otro caso (vacío, no aprobado, artefacto alterado) usa el
 * {@link RulesScorer}. Así activar o volver atrás un modelo es solo cambiar un parámetro.
 */
@Component
public class ScorerFactory {

    private static final Logger log = LoggerFactory.getLogger(ScorerFactory.class);

    private final MlModelRepository models;
    private final MlModelRuntime runtime;

    public ScorerFactory(MlModelRepository models, MlModelRuntime runtime) {
        this.models = models;
        this.runtime = runtime;
    }

    public RiskScorer create(RiskSettings.Snapshot settings) {
        RulesScorer rules = new RulesScorer(RiskRules.all(), settings.rules(), settings.levels());
        if (settings.activeMlVersion().isEmpty()) {
            return rules;
        }
        Optional<MlModel> model = models.findByModelVersion(settings.activeMlVersion())
                .filter(m -> m.getStatus() == MlModel.Status.APROBADO);
        if (model.isEmpty()) {
            log.warn("risk.ml.active-version apunta a un modelo inexistente o no aprobado; se usan reglas");
            return rules;
        }
        return runtime.load(model.get())
                .<RiskScorer>map(m -> new MlScorer(m, settings.mlLevels(), rules))
                .orElseGet(() -> {
                    log.warn("No se pudo cargar el modelo ML activo; se usan reglas");
                    return rules;
                });
    }
}
