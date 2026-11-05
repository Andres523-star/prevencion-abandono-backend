package co.edu.retencion.risk.application;

import java.util.UUID;

import org.springframework.stereotype.Service;

import co.edu.retencion.risk.RiskAnalysis;
import co.edu.retencion.risk.RiskQueries.LatestRisk;
import co.edu.retencion.risk.RiskTrigger;
import co.edu.retencion.risk.domain.RiskAssessment;

/** Implementación de {@link RiskAnalysis}: reanálisis con la misma política del orquestador. */
@Service
public class RiskAnalysisService implements RiskAnalysis {

    private final RiskOrchestrator orchestrator;

    public RiskAnalysisService(RiskOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public LatestRisk reanalyze(UUID studentId, UUID requestedBy) {
        RiskAssessment a = orchestrator.analyze(studentId, RiskTrigger.INTERVENTION, requestedBy, null);
        return new LatestRisk(a.getId(), a.getLevel(), a.getScore(), a.getScoreType(), a.getCreatedAt());
    }
}
