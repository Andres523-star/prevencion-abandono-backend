package co.edu.retencion.justifications;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Justificaciones de un estudiante, para la copia de sus datos (derecho de acceso). */
public interface JustificationQueries {

    List<JustificationRecord> ofStudent(UUID studentId);

    record JustificationRecord(UUID id, String category, String status, String description, String reviewComment,
                               String appealText, Instant submittedAt, Instant reviewedAt) {
        @Override
        public String toString() {
            return "JustificationRecord[" + id + "]";
        }
    }
}
