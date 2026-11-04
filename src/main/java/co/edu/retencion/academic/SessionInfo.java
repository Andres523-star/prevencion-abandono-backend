package co.edu.retencion.academic;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Sesión de clase.
 *
 * @param id      id
 * @param groupId grupo
 * @param termId  periodo del grupo
 * @param date    fecha
 * @param start   hora de inicio
 * @param end     hora de fin
 * @param status  PROGRAMADA, DICTADA o CANCELADA
 */
public record SessionInfo(UUID id, UUID groupId, UUID termId, LocalDate date, LocalTime start, LocalTime end,
                          String status) {

    public boolean isCancelled() {
        return "CANCELADA".equals(status);
    }
}
