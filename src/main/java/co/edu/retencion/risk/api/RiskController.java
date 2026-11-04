package co.edu.retencion.risk.api;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import co.edu.retencion.audit.Audited;
import co.edu.retencion.audit.SensitiveRead;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.common.security.authorization.RequireScope;
import co.edu.retencion.common.web.CursorPage;
import co.edu.retencion.risk.RiskTrigger;
import co.edu.retencion.risk.application.BiasMetricsService;
import co.edu.retencion.risk.application.RiskAssessmentService;
import co.edu.retencion.risk.application.RiskAssessmentService.AssessmentView;
import co.edu.retencion.risk.application.RiskBatchLauncher;
import co.edu.retencion.risk.application.RiskOrchestrator;
import co.edu.retencion.risk.domain.BiasMetrics;

/**
 * Análisis de riesgo: manual, historial (cada análisis es una fila nueva), lote nocturno y métricas
 * de sesgo. Las respuestas aplican el mínimo necesario por rol.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Motor de riesgo")
public class RiskController {

    private final RiskOrchestrator orchestrator;
    private final RiskAssessmentService assessments;
    private final BiasMetricsService bias;
    private final RiskBatchLauncher batch;

    public RiskController(RiskOrchestrator orchestrator, RiskAssessmentService assessments, BiasMetricsService bias,
                          RiskBatchLauncher batch) {
        this.orchestrator = orchestrator;
        this.assessments = assessments;
        this.bias = bias;
        this.batch = batch;
    }

    @PostMapping("/students/{id}/analyze")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','CONSEJERO','PSICOLOGO','COORDINADOR')")
    @RequireScope(resource = "STUDENT", id = "#id")
    @Audited(action = "RISK_ANALYZE", entity = "Student", entityId = "#id")
    @Operation(summary = "Analiza el riesgo del estudiante ahora (reglas/ML + explicación de IA)")
    public AssessmentView analyze(@PathVariable UUID id) {
        var user = CurrentUser.require();
        return assessments.view(orchestrator.analyze(id, RiskTrigger.MANUAL, user.userId(), null), user.roles());
    }

    @GetMapping("/students/{id}/risk-assessments")
    @PreAuthorize("hasAnyRole('ADMIN','CONSEJERO','PSICOLOGO','COORDINADOR','DOCENTE')")
    @RequireScope(resource = "STUDENT", id = "#id")
    @SensitiveRead(entity = "RiskAssessment", entityId = "#id", requireReason = false)
    @Operation(summary = "Historial de análisis de riesgo (keyset, del más reciente al más antiguo)")
    public CursorPage<AssessmentView> history(@PathVariable UUID id, @RequestParam(required = false) UUID after,
                                              @RequestParam(required = false) Integer size) {
        return assessments.history(id, after, size);
    }

    @GetMapping("/students/{id}/risk-assessments/latest")
    @PreAuthorize("hasAnyRole('ADMIN','CONSEJERO','PSICOLOGO','COORDINADOR','DOCENTE')")
    @RequireScope(resource = "STUDENT", id = "#id")
    @SensitiveRead(entity = "RiskAssessment", entityId = "#id", requireReason = false)
    @Operation(summary = "Último análisis de riesgo")
    public ResponseEntity<AssessmentView> latest(@PathVariable UUID id) {
        return assessments.latestView(id).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/risk/bias-metrics")
    @PreAuthorize("hasAnyRole('ADMIN','CONSEJERO','COORDINADOR')")
    @Operation(summary = "Distribución del riesgo por programa, estrato y semestre (celdas pequeñas suprimidas)")
    public BiasMetrics.Report biasMetrics() {
        return bias.report();
    }

    @PostMapping("/risk/batch-runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "RISK_BATCH_LAUNCH", entity = "RiskBatch")
    @Operation(summary = "Lanza el lote nocturno de análisis ahora (en segundo plano)")
    public void launchBatch() {
        Thread.ofVirtual().name("risk-batch-manual").start(batch::run);
    }
}
