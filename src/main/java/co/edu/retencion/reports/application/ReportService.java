package co.edu.retencion.reports.application;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.analytics.AnalyticsFilter;
import co.edu.retencion.analytics.AnalyticsViewer;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.files.ObjectStorage;
import co.edu.retencion.common.security.AuthenticatedUser;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.common.web.CursorPage;
import co.edu.retencion.reports.domain.ReportJob;
import co.edu.retencion.reports.domain.ReportJobRepository;

/**
 * Solicitud y consulta de reportes. El alcance se resuelve aquí, con el usuario de la petición, y queda
 * guardado en el trabajo. Cada quien ve solo sus reportes; ADMIN puede ver todos.
 */
@Service
public class ReportService {

    private static final Duration URL_LIFE = Duration.ofMinutes(10);

    private final ReportJobRepository jobs;
    private final ObjectStorage storage;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ReportService(ReportJobRepository jobs, ObjectStorage storage, ObjectMapper mapper, Clock clock) {
        this.jobs = jobs;
        this.storage = storage;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Estado de un reporte, sin la clave del archivo. */
    public record ReportView(UUID id, ReportJob.Type type, ReportJob.Format format, ReportJob.Status status,
                             boolean includeIdentity, Long rowCount, Long sizeBytes, String failureCode, Instant createdAt,
                             Instant finishedAt, Instant expiresAt) {

        static ReportView of(ReportJob j) {
            return new ReportView(j.getId(), j.getType(), j.getFormat(), j.getStatus(), j.isIncludeIdentity(), j.getRowCount(),
                    j.getSizeBytes(), j.getFailureCode(), j.getCreatedAt(), j.getFinishedAt(), j.getExpiresAt());
        }
    }

    /** URL prefirmada de descarga. */
    public record DownloadView(URI url, Instant expiresAt, String fileName) {
    }

    @Transactional
    public ReportView request(AuthenticatedUser user, ReportJob.Type type, ReportJob.Format format, AnalyticsFilter filter,
                              boolean includeIdentity, String reason) {
        if (includeIdentity && !type.listsStudents()) {
            throw DomainException.badRequest("Este reporte es agregado: no incluye identidad");
        }
        if (includeIdentity && (reason == null || reason.isBlank())) {
            throw new DomainException(ReportProblems.REASON_REQUIRED, "Indica el motivo en la cabecera X-Access-Reason");
        }
        AnalyticsViewer viewer = AnalyticsViewer.of(user);
        viewer.effective(filter.programId());
        ReportJob job = ReportJob.request(user.userId(), type, format, json(filter),
                viewer.programs() == null ? null : json(viewer.programs()), includeIdentity, clock.instant());
        return ReportView.of(jobs.save(job));
    }

    @Transactional(readOnly = true)
    public ReportView get(AuthenticatedUser user, UUID id) {
        return ReportView.of(load(user, id));
    }

    @Transactional(readOnly = true)
    public CursorPage<ReportView> mine(AuthenticatedUser user, UUID before, Integer size) {
        int limit = CursorPage.clampSize(size);
        List<ReportView> rows = jobs.mine(user.userId(), before == null, before, Limit.of(limit + 1)).stream()
                .map(ReportView::of).toList();
        return CursorPage.of(rows, limit, ReportView::id);
    }

    @Transactional(readOnly = true)
    public DownloadView downloadUrl(AuthenticatedUser user, UUID id) {
        ReportJob job = load(user, id);
        Instant now = clock.instant();
        if (job.getStatus() == ReportJob.Status.EXPIRADO
                || (job.getStatus() == ReportJob.Status.LISTO && !job.downloadable(now))) {
            throw new DomainException(ReportProblems.EXPIRED, "El reporte venció; solicítalo de nuevo");
        }
        if (job.getStatus() != ReportJob.Status.LISTO) {
            throw new DomainException(ReportProblems.NOT_READY, "El reporte todavía no está listo");
        }
        return new DownloadView(storage.presignedDownloadUrl(job.getObjectKey(), URL_LIFE, job.getFileName()),
                now.plus(URL_LIFE), job.getFileName());
    }

    /** No revela si existe un reporte ajeno. */
    private ReportJob load(AuthenticatedUser user, UUID id) {
        return jobs.findById(id).filter(j -> j.getRequestedBy().equals(user.userId()) || user.hasRole(Role.ADMIN))
                .orElseThrow(() -> DomainException.notFound("Reporte no encontrado"));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value instanceof Set<?> set ? List.copyOf(set) : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
