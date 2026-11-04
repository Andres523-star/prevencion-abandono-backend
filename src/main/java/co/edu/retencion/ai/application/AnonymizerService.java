package co.edu.retencion.ai.application;

import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

import co.edu.retencion.ai.Anonymizer;
import co.edu.retencion.ai.CaseFacts;
import co.edu.retencion.ai.PayloadRejectedException;
import co.edu.retencion.ai.SupportResourceInfo;
import co.edu.retencion.ai.domain.AiPayload;
import co.edu.retencion.ai.domain.PayloadWhitelist;
import co.edu.retencion.ai.domain.PiiRedactor;
import co.edu.retencion.settings.SettingsProvider;

/**
 * Implementación de {@link Anonymizer}. Las decisiones de privacidad dependen de parámetros editables
 * en caliente: {@code ai.free-text.enabled} (texto libre, por defecto NO) y
 * {@code ai.payload.include-sensitive-signals} (bienestar y causas de salud, por defecto NO).
 */
@Service
public class AnonymizerService implements Anonymizer {

    static final String FREE_TEXT_KEY = "ai.free-text.enabled";
    static final String SENSITIVE_KEY = "ai.payload.include-sensitive-signals";

    private final ObjectMapper mapper;
    private final PiiRedactor redactor;
    private final PayloadWhitelist whitelist;
    private final SettingsProvider settings;

    public AnonymizerService(ObjectMapper mapper, PiiRedactor redactor, PayloadWhitelist whitelist,
                             SettingsProvider settings) {
        this.mapper = mapper;
        this.redactor = redactor;
        this.whitelist = whitelist;
        this.settings = settings;
    }

    @Override
    public AnonymizedPayload anonymize(CaseFacts facts, List<SupportResourceInfo> resources, PlanTarget planTarget) {
        boolean includeSensitive = settings.getBoolean(SENSITIVE_KEY, false);
        AiPayload.Builder builder = AiPayload.builder(mapper, redactor, whitelist).facts(facts, includeSensitive);
        if (settings.getBoolean(FREE_TEXT_KEY, false)) {
            builder.freeTexts(facts.freeTexts());
        }
        builder.resources(resources.stream().sorted(Comparator.comparing(SupportResourceInfo::id)).toList())
                .plan(planTarget);
        AiPayload payload;
        try {
            payload = builder.build();
        } catch (PayloadWhitelist.PayloadPolicyException ex) {
            throw new PayloadRejectedException(ex.violations());
        }
        return new AnonymizedPayload(payload.json(), payload.sha256(), builder.sensitive(), builder.freeTextIncluded());
    }
}
