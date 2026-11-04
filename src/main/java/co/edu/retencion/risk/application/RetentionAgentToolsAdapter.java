package co.edu.retencion.risk.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.ai.RetentionAgentTools;
import co.edu.retencion.risk.domain.RiskAssessmentRepository;
import co.edu.retencion.risk.domain.RiskScorer.ScoreResult;

/**
 * Implementa la SPI de solo lectura del agente de retención con los datos del motor. Solo entrega
 * hechos seudonimizados y códigos; el módulo de IA vuelve a pasarlos por la lista blanca.
 */
@Component
public class RetentionAgentToolsAdapter implements RetentionAgentTools {

    private final RiskFeatureCollector collector;
    private final RiskSettings settings;
    private final ScorerFactory scorers;
    private final RiskAssessmentRepository assessments;
    private final ObjectMapper mapper;

    public RetentionAgentToolsAdapter(RiskFeatureCollector collector, RiskSettings settings, ScorerFactory scorers,
                                      RiskAssessmentRepository assessments, ObjectMapper mapper) {
        this.collector = collector;
        this.settings = settings;
        this.scorers = scorers;
        this.assessments = assessments;
        this.mapper = mapper;
    }

    @Override
    public Optional<AgentCase> caseOf(UUID studentId) {
        return collector.collect(studentId).map(f -> {
            RiskSettings.Snapshot snapshot = settings.snapshot();
            ScoreResult score = scorers.create(snapshot).score(f);
            return new AgentCase(collector.caseFacts(f, score, collector.freeTexts(studentId, snapshot.freeText())),
                    collector.eligibility(f));
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<HistoryEntry> history(UUID studentId, int limit) {
        return assessments.history(studentId, true, null, Limit.of(Math.max(1, Math.min(limit, 20)))).stream()
                .map(a -> new HistoryEntry(a.getCreatedAt(), a.getLevel().name(), a.getScore(), a.getScoreType().name(),
                        a.getSource().name(), codes(a.getFactors())))
                .toList();
    }

    private List<String> codes(String factorsJson) {
        try {
            JsonNode node = mapper.readTree(factorsJson);
            return java.util.stream.StreamSupport.stream(node.spliterator(), false)
                    // Los factores de bienestar no salen hacia la IA por esta vía.
                    .filter(n -> !"BIENESTAR".equals(n.path("category").asText()))
                    .map(n -> n.path("code").asText()).distinct().toList();
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }
}
