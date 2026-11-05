package co.edu.retencion.reports.api;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import co.edu.retencion.analytics.AnalyticsFilter;
import co.edu.retencion.audit.Audited;
import co.edu.retencion.audit.SensitiveRead;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.common.web.CursorPage;
import co.edu.retencion.reports.application.ReportService;
import co.edu.retencion.reports.application.ReportService.DownloadView;
import co.edu.retencion.reports.application.ReportService.ReportView;
import co.edu.retencion.reports.domain.ReportJob;

/** Reportes asíncronos: se piden, se consulta el estado y se descargan con URL prefirmada. */
@RestController
@RequestMapping("/api/v1/reports")
@PreAuthorize("hasAnyRole('ADMIN','CONSEJERO','PSICOLOGO','COORDINADOR')")
@Tag(name = "Reportes")
public class ReportController {

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    /** Mismos filtros de la analítica; {@code includeIdentity} agrega los nombres a los listados. */
    public record CreateRequest(@NotNull ReportJob.Type type, @NotNull ReportJob.Format format, UUID programId,
                                @Min(1) @Max(14) Integer semester, @Min(1990) @Max(2100) Integer year, UUID termId,
                                LocalDate from, LocalDate to, AnalyticsFilter.RiskBucket riskLevel, boolean includeIdentity) {

        AnalyticsFilter filter() {
            return new AnalyticsFilter(programId, semester, year, termId, from, to, riskLevel).validated();
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Audited(action = "REPORT_REQUEST", entity = "Report", entityId = "#result.id")
    @Operation(summary = "Solicita un reporte (PDF o Excel); con identidad exige X-Access-Reason")
    public ReportView create(@Valid @RequestBody CreateRequest request,
                             @RequestHeader(name = "X-Access-Reason", required = false) String reason) {
        return reports.request(CurrentUser.require(), request.type(), request.format(), request.filter(),
                request.includeIdentity(), reason);
    }

    @GetMapping
    @Operation(summary = "Mis reportes")
    public CursorPage<ReportView> mine(@RequestParam(required = false) UUID before,
                                       @RequestParam(required = false) Integer size) {
        return reports.mine(CurrentUser.require(), before, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Estado de un reporte")
    public ReportView get(@PathVariable UUID id) {
        return reports.get(CurrentUser.require(), id);
    }

    @GetMapping("/{id}/download-url")
    @SensitiveRead(entity = "Report", entityId = "#id", requireReason = false)
    @Operation(summary = "URL prefirmada de 10 minutos (auditada)")
    public DownloadView download(@PathVariable UUID id) {
        return reports.downloadUrl(CurrentUser.require(), id);
    }
}
