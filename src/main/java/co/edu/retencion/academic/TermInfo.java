package co.edu.retencion.academic;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Periodo académico.
 *
 * @param id        id
 * @param code      código, p. ej. {@code 2026-2}
 * @param startDate primer día de clases
 * @param endDate   último día de clases
 * @param status    PLANNED, ACTIVE o CLOSED
 */
public record TermInfo(UUID id, String code, LocalDate startDate, LocalDate endDate, String status) {
}
