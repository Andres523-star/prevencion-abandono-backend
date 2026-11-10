package co.edu.retencion.justifications.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import co.edu.retencion.audit.Audited;
import co.edu.retencion.audit.SensitiveRead;
import co.edu.retencion.justifications.application.EvidenceService;
import co.edu.retencion.justifications.application.JustificationService;
import co.edu.retencion.justifications.application.JustificationViews.DecisionView;
import co.edu.retencion.justifications.application.JustificationViews.DownloadView;
import co.edu.retencion.justifications.application.JustificationViews.EvidenceView;
import co.edu.retencion.justifications.application.JustificationViews.JustificationView;
import co.edu.retencion.justifications.domain.AbsenceJustification;

/**
 * Justificaciones de faltas. El estudiante las envía con evidencias; solo el docente de la materia
 * las aprueba o rechaza. El control fino (titular, docente asignado) lo hace el servicio.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Justificaciones")
public class JustificationController {

    private final JustificationService justifications;
    private final EvidenceService evidences;

    public JustificationController(JustificationService justifications, EvidenceService evidences) {
        this.justifications = justifications;
        this.evidences = evidences;
    }

    public record SubmitRequest(@NotNull UUID attendanceRecordId, @NotNull AbsenceJustification.Category category,
                                @NotNull @Size(min = 1, max = 2000) String description) {
        @Override
        public String toString() {
            return "SubmitRequest[attendanceRecordId=" + attendanceRecordId + ", category=" + category + "]";
        }
    }

    public record DecisionRequest(@NotNull Boolean approved, @Size(max = 1000) String comment) {
    }

    @PostMapping("/justifications")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ESTUDIANTE')")
    @Audited(action = "JUSTIFICATION_SUBMIT", entity = "AbsenceJustification")
    @Operation(summary = "El estudiante justifica una de sus faltas (dentro del plazo)")
    public JustificationView submit(@Valid @RequestBody SubmitRequest request) {
        return justifications.submit(request.attendanceRecordId(), request.category(), request.description());
    }

    @PostMapping(value = "/justifications/{id}/evidences", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ESTUDIANTE')")
    @Audited(action = "JUSTIFICATION_EVIDENCE_UPLOAD", entity = "AbsenceJustification", entityId = "#id")
    @Operation(summary = "Adjunta una evidencia (PDF/JPG/PNG); pasa por antivirus antes de quedar disponible")
    public EvidenceView upload(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        try {
            return evidences.upload(id, file.getOriginalFilename(), file.getBytes());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @GetMapping("/me/justifications")
    @PreAuthorize("hasRole('ESTUDIANTE')")
    @Operation(summary = "Mis justificaciones y su estado")
    public List<JustificationView> mine() {
        return justifications.mine();
    }

    @GetMapping("/justifications/pending")
    @PreAuthorize("hasRole('DOCENTE')")
    @Operation(summary = "Bandeja del docente: justificaciones pendientes de sus grupos")
    public List<JustificationView> pending() {
        return justifications.pendingForCurrentTeacher();
    }

    @GetMapping("/justifications/{id}")
    @PreAuthorize("hasAnyRole('ESTUDIANTE','DOCENTE','ADMIN')")
    @SensitiveRead(entity = "AbsenceJustification", entityId = "#id", requireReason = false)
    @Operation(summary = "Detalle de una justificación (titular, docente asignado o administración)")
    public JustificationView get(@PathVariable UUID id) {
        return justifications.get(id);
    }

    @PostMapping("/justifications/{id}/decision")
    @PreAuthorize("isAuthenticated()")
    @Audited(action = "JUSTIFICATION_DECIDE", entity = "AbsenceJustification", entityId = "#id")
    @Operation(summary = "Aprueba o rechaza (con comentario) la justificación: solo el docente de la materia")
    public DecisionView decide(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
        return justifications.decide(id, request.approved(), request.comment());
    }

    public record AppealRequest(@NotBlank @Size(max = 2000) String text) {
        @Override
        public String toString() {
            return "AppealRequest[***]";
        }
    }

    @PostMapping("/justifications/{id}/appeal")
    @PreAuthorize("hasRole('ESTUDIANTE')")
    @Audited(action = "JUSTIFICATION_APPEAL", entity = "AbsenceJustification", entityId = "#id")
    @Operation(summary = "Apela una justificación rechazada (una vez, dentro del plazo); decide el docente de la materia")
    public JustificationView appeal(@PathVariable UUID id, @Valid @RequestBody AppealRequest request) {
        return justifications.appeal(id, request.text());
    }

    @GetMapping("/evidences/{id}/download-url")
    @PreAuthorize("hasAnyRole('ESTUDIANTE','DOCENTE','ADMIN')")
    @SensitiveRead(entity = "JustificationEvidence", entityId = "#id", requireReason = false)
    @Operation(summary = "URL prefirmada de corta duración para descargar una evidencia")
    public DownloadView downloadUrl(@PathVariable UUID id) {
        return evidences.downloadUrl(id);
    }

    @PostMapping("/justifications/expire")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "JUSTIFICATION_EXPIRE_RUN", entity = "AbsenceJustification")
    @Operation(summary = "Ejecuta ahora el vencimiento de faltas sin justificar (normalmente es diario)")
    public int expire() {
        return justifications.expireOverdue();
    }
}
