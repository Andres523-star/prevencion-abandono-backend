package co.edu.retencion.justifications.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.files.FileTypes;
import co.edu.retencion.common.files.MalwareScanner;
import co.edu.retencion.common.files.ObjectStorage;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.justifications.application.JustificationViews.DownloadView;
import co.edu.retencion.justifications.application.JustificationViews.EvidenceView;
import co.edu.retencion.justifications.domain.AbsenceJustification;
import co.edu.retencion.justifications.domain.JustificationEvidence;
import co.edu.retencion.justifications.domain.JustificationEvidenceRepository;

/**
 * Evidencias: validación por magic bytes (PDF/JPG/PNG), tamaño máximo, extensión coherente,
 * nombre aleatorio, antivirus antes de quedar disponible y descarga solo con URL prefirmada de
 * corta duración (auditada en el controlador).
 */
@Service
public class EvidenceService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceService.class);

    private final JustificationEvidenceRepository evidences;
    private final JustificationService justifications;
    private final ObjectStorage storage;
    private final MalwareScanner scanner;
    private final EvidenceProperties properties;
    private final Clock clock;

    public EvidenceService(JustificationEvidenceRepository evidences, JustificationService justifications,
                           ObjectStorage storage, MalwareScanner scanner, EvidenceProperties properties, Clock clock) {
        this.evidences = evidences;
        this.justifications = justifications;
        this.storage = storage;
        this.scanner = scanner;
        this.properties = properties;
        this.clock = clock;
    }

    /** El estudiante titular adjunta una evidencia a su justificación pendiente. */
    @Transactional
    public EvidenceView upload(UUID justificationId, String fileName, byte[] content) {
        AbsenceJustification justification = justifications.load(justificationId);
        if (!justification.getStudentId().equals(justifications.currentStudentId())) {
            throw new AccessDeniedException("Solo el estudiante puede adjuntar evidencias a su justificación");
        }
        if (justification.getStatus() != AbsenceJustification.Status.PENDIENTE) {
            throw DomainException.conflict("La justificación ya fue revisada");
        }
        if (evidences.countByJustificationId(justificationId) >= properties.maxEvidencesPerRequest()) {
            throw DomainException.badRequest("Se alcanzó el máximo de evidencias por justificación");
        }
        FileTypes.Kind kind = FileTypes.requireAllowed(fileName, content, properties.maxEvidenceSize().toBytes());

        // Antivirus ANTES de guardar: un archivo infectado nunca llega al almacenamiento.
        MalwareScanner.ScanResult scan = scanner.scan(content);
        if (scan.verdict() == MalwareScanner.Verdict.INFECTED) {
            log.warn("Evidencia rechazada por el antivirus (firma {})", scan.signature());
            throw new DomainException(JustificationProblems.FILE_INFECTED, "El archivo fue rechazado por el antivirus");
        }
        JustificationEvidence evidence = JustificationEvidence.uploaded(justificationId, kind.defaultExtension(),
                kind.mediaType(), content.length, sha256(content), CurrentUser.require().userId(), clock.instant());
        storage.put(evidence.getObjectKey(), content, kind.mediaType());
        evidence.scanned(scan.verdict() == MalwareScanner.Verdict.CLEAN
                ? JustificationEvidence.ScanStatus.LIMPIO : JustificationEvidence.ScanStatus.ERROR, clock.instant());
        return EvidenceView.of(evidences.save(evidence));
    }

    /** URL prefirmada para el titular, el docente asignado o ADMIN. */
    @Transactional(readOnly = true)
    public DownloadView downloadUrl(UUID evidenceId) {
        JustificationEvidence evidence = evidences.findById(evidenceId)
                .orElseThrow(() -> DomainException.notFound("Evidencia no encontrada"));
        justifications.requireCanView(justifications.load(evidence.getJustificationId()), CurrentUser.require());
        if (!evidence.isAvailable()) {
            throw new DomainException(JustificationProblems.EVIDENCE_NOT_AVAILABLE, "La evidencia no está disponible");
        }
        var url = storage.presignedDownloadUrl(evidence.getObjectKey(), properties.downloadUrlTtl(),
                evidence.downloadName());
        return new DownloadView(url.toString(), clock.instant().plus(properties.downloadUrlTtl()));
    }

    /** Reintenta el escaneo de evidencias que quedaron en ERROR (antivirus caído). */
    @Scheduled(fixedDelayString = "${app.justifications.rescan-interval-ms:300000}")
    @Transactional
    public void rescanPending() {
        for (JustificationEvidence evidence : evidences.findByScanStatusIn(
                EnumSet.of(JustificationEvidence.ScanStatus.PENDIENTE, JustificationEvidence.ScanStatus.ERROR))) {
            MalwareScanner.ScanResult scan = scanner.scan(storage.get(evidence.getObjectKey()));
            switch (scan.verdict()) {
                case CLEAN -> evidence.scanned(JustificationEvidence.ScanStatus.LIMPIO, clock.instant());
                case INFECTED -> {
                    storage.delete(evidence.getObjectKey());
                    evidence.scanned(JustificationEvidence.ScanStatus.INFECTADO, clock.instant());
                }
                case ERROR -> log.debug("Antivirus aún no disponible");
            }
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
