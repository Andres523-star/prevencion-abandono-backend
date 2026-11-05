package co.edu.retencion.retention;

import java.util.UUID;

/** Un caso de retención se abrió, cambió de etapa o se cerró (lo consume la analítica). */
public record RetentionCaseChanged(UUID caseId, UUID studentId) {
}
