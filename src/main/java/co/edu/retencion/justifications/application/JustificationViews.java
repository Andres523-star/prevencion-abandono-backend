package co.edu.retencion.justifications.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import co.edu.retencion.justifications.domain.AbsenceJustification;
import co.edu.retencion.justifications.domain.JustificationEvidence;
import co.edu.retencion.records.CounterInfo;

/** DTOs de justificaciones. */
public final class JustificationViews {

    private JustificationViews() {
    }

    public record EvidenceView(UUID id, String mediaType, long sizeBytes, JustificationEvidence.ScanStatus scanStatus,
                               Instant uploadedAt) {
        static EvidenceView of(JustificationEvidence e) {
            return new EvidenceView(e.getId(), e.getMediaType(), e.getSizeBytes(), e.getScanStatus(), e.getUploadedAt());
        }
    }

    /** Detalle (contiene la descripción: solo para el estudiante titular y el docente revisor). */
    public record JustificationView(UUID id, UUID attendanceRecordId, UUID studentId, UUID groupId,
                                    AbsenceJustification.Category category, String description,
                                    AbsenceJustification.Status status, LocalDate deadline, Instant submittedAt,
                                    Instant reviewedAt, String reviewComment, String appealText, Instant appealedAt,
                                    List<EvidenceView> evidences) {
        static JustificationView of(AbsenceJustification j, List<JustificationEvidence> evidences) {
            return new JustificationView(j.getId(), j.getAttendanceRecordId(), j.getStudentId(), j.getGroupId(),
                    j.getCategory(), j.getDescription(), j.getStatus(), j.getDeadline(), j.getSubmittedAt(),
                    j.getReviewedAt(), j.getReviewComment(), j.getAppealText(), j.getAppealedAt(),
                    evidences.stream().map(EvidenceView::of).toList());
        }

        @Override
        public String toString() {
            return "JustificationView[id=" + id + ", status=" + status + "]";
        }
    }

    /** Resultado de la decisión del docente con el contador actualizado. */
    public record DecisionView(JustificationView justification, CounterInfo counter) {
    }

    /** URL de descarga prefirmada de corta duración. */
    public record DownloadView(String url, Instant expiresAt) {
    }
}
