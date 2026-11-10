package co.edu.retencion.privacy.application;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.audit.AuditOutcome;
import co.edu.retencion.audit.AuditTrail;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.files.ObjectStorage;
import co.edu.retencion.common.id.UuidV7;
import co.edu.retencion.common.security.AuthenticatedUser;
import co.edu.retencion.common.time.TimeConfig;
import co.edu.retencion.privacy.DataRequestAnswered;
import co.edu.retencion.privacy.DataRequestSubmitted;
import co.edu.retencion.privacy.StudentDataSuppressed;
import co.edu.retencion.privacy.domain.BusinessDays;
import co.edu.retencion.privacy.domain.DataSubjectRequest;
import co.edu.retencion.privacy.domain.DataSubjectRequestRepository;
import co.edu.retencion.settings.SettingsProvider;
import co.edu.retencion.students.StudentRegistry;
import co.edu.retencion.users.UserDirectory;

/**
 * Solicitudes del titular. El estudiante las radica desde el portal; las atiende ADMIN (en el rol de oficial
 * de protección de datos) dentro del plazo legal. Cada paso queda en la auditoría.
 */
@Service
public class DataRequestService {

    private static final Duration URL_LIFE = Duration.ofMinutes(10);

    private final DataSubjectRequestRepository requests;
    private final StudentRegistry students;
    private final UserDirectory users;
    private final AcademicDirectory academic;
    private final DataExportBuilder exports;
    private final ObjectStorage storage;
    private final AuditTrail audit;
    private final SettingsProvider settings;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public DataRequestService(DataSubjectRequestRepository requests, StudentRegistry students, UserDirectory users,
                              AcademicDirectory academic, DataExportBuilder exports, ObjectStorage storage, AuditTrail audit,
                              SettingsProvider settings, ApplicationEventPublisher events, Clock clock) {
        this.requests = requests;
        this.students = students;
        this.users = users;
        this.academic = academic;
        this.exports = exports;
        this.storage = storage;
        this.audit = audit;
        this.settings = settings;
        this.events = events;
        this.clock = clock;
    }

    /** Vista de la solicitud (sin la clave del archivo). */
    public record RequestView(UUID id, UUID studentId, DataSubjectRequest.Type type, DataSubjectRequest.Status status,
                              String detail, LocalDate dueOn, boolean overdue, String decisionComment, Instant decidedAt,
                              Instant executedAt, boolean exportAvailable, Instant exportExpiresAt, Instant createdAt) {

        @Override
        public String toString() {
            return "RequestView[" + id + "]";
        }
    }

    public record DownloadView(URI url, Instant expiresAt) {
    }

    @Transactional
    public RequestView submit(AuthenticatedUser user, DataSubjectRequest.Type type, String detail) {
        UUID studentId = studentOf(user);
        LocalDate today = today();
        int days = type.isClaim() ? settings.getInt("privacy.claim-response-business-days", 15)
                : settings.getInt("privacy.access-response-business-days", 10);
        LocalDate due = BusinessDays.add(today, days, academic.holidays(today, today.plusDays(days * 2L + 14)));
        DataSubjectRequest saved;
        try {
            saved = requests.saveAndFlush(DataSubjectRequest.submit(studentId, user.userId(), type, detail, due, clock.instant()));
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw DomainException.conflict("Ya tienes una solicitud de este tipo en trámite");
        }
        events.publishEvent(new DataRequestSubmitted(saved.getId(), studentId, type.name()));
        return view(saved);
    }

    @Transactional(readOnly = true)
    public List<RequestView> mine(AuthenticatedUser user) {
        return requests.findByStudentIdOrderByCreatedAtDesc(studentOf(user)).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<RequestView> queue(DataSubjectRequest.Status status) {
        return requests.queue(status == null, status).stream().map(this::view).toList();
    }

    /** Aprobar. Una consulta se responde en el acto con la copia de los datos. */
    @Transactional
    public RequestView approve(AuthenticatedUser admin, UUID id, String comment) {
        DataSubjectRequest r = load(id);
        r.approve(admin.userId(), comment, clock.instant());
        if (r.getType() == DataSubjectRequest.Type.ACCESO) {
            String key = "privacidad/" + UuidV7.newId() + "-json";
            storage.put(key, exports.build(r.getStudentId()), "application/json");
            Duration life = Duration.ofHours(Math.max(1, settings.getInt("privacy.export-expiry-hours", 72)));
            r.attachExport(key, clock.instant().plus(life), clock.instant());
            r.execute(admin.userId(), null, clock.instant());
            audit.record("DATA_EXPORT_GENERATED", "DataSubjectRequest", id.toString(), AuditOutcome.SUCCESS,
                    Map.of("studentId", r.getStudentId().toString()));
        }
        requests.save(r);
        answered(r);
        return view(r);
    }

    @Transactional
    public RequestView reject(AuthenticatedUser admin, UUID id, String comment) {
        DataSubjectRequest r = load(id);
        r.reject(admin.userId(), comment, clock.instant());
        requests.save(r);
        answered(r);
        return view(r);
    }

    /**
     * Ejecutar una solicitud aprobada. La rectificación registra lo corregido (la corrección se hace con los
     * endpoints de cada módulo). La supresión borra los datos personales y avisa a los demás módulos.
     */
    @Transactional
    public RequestView execute(AuthenticatedUser admin, UUID id, String note) {
        DataSubjectRequest r = load(id);
        r.execute(admin.userId(), note, clock.instant());
        if (r.getType() == DataSubjectRequest.Type.SUPRESION) {
            UUID user = students.suppressPersonalData(r.getStudentId()).orElse(null);
            if (user != null) {
                users.deactivate(user);
            }
            events.publishEvent(new StudentDataSuppressed(r.getStudentId(), user));
            audit.record("STUDENT_DATA_SUPPRESSED", "Student", r.getStudentId().toString(), AuditOutcome.SUCCESS,
                    Map.of("requestId", id.toString()));
        }
        requests.save(r);
        answered(r);
        return view(r);
    }

    /** Solo el titular descarga su copia, mientras no venza. */
    @Transactional(readOnly = true)
    public DownloadView exportUrl(AuthenticatedUser user, UUID id) {
        DataSubjectRequest r = load(id);
        if (!r.getRequestedBy().equals(user.userId())) {
            throw DomainException.notFound("Solicitud no encontrada");
        }
        if (!r.exportAvailable(clock.instant())) {
            throw DomainException.conflict("La copia de tus datos no está disponible; solicítala de nuevo");
        }
        return new DownloadView(storage.presignedDownloadUrl(r.getExportObjectKey(), URL_LIFE, "mis-datos.json"),
                clock.instant().plus(URL_LIFE));
    }

    /** Borra las copias vencidas. @return cantidad */
    @Scheduled(cron = "${app.privacy.export-expiry-cron:0 20 * * * *}", zone = "America/Bogota")
    @Transactional
    public int expireExports() {
        int expired = 0;
        for (DataSubjectRequest r : requests.expiredExports(clock.instant())) {
            storage.delete(r.getExportObjectKey());
            r.expireExport(clock.instant());
            requests.save(r);
            expired++;
        }
        return expired;
    }

    private void answered(DataSubjectRequest r) {
        events.publishEvent(new DataRequestAnswered(r.getId(), r.getStudentId(), r.getRequestedBy(), r.getStatus().name()));
    }

    private DataSubjectRequest load(UUID id) {
        return requests.findById(id).orElseThrow(() -> DomainException.notFound("Solicitud no encontrada"));
    }

    private UUID studentOf(AuthenticatedUser user) {
        return students.findStudentIdByUser(user.userId())
                .orElseThrow(() -> new AccessDeniedException("Tu usuario no está vinculado a un estudiante"));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }

    private RequestView view(DataSubjectRequest r) {
        Instant now = clock.instant();
        return new RequestView(r.getId(), r.getStudentId(), r.getType(), r.getStatus(), r.getDetail(), r.getDueOn(),
                r.isOverdue(today()), r.getDecisionComment(), r.getDecidedAt(), r.getExecutedAt(), r.exportAvailable(now),
                r.getExportExpiresAt(), r.getCreatedAt());
    }
}
