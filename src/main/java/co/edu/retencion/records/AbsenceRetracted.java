package co.edu.retencion.records;

import java.util.UUID;

/**
 * Evento: una falta se corrigió (p. ej. el docente se equivocó y la cambió a PRESENTE). El módulo
 * de correo cancela el aviso pendiente.
 *
 * @param recordId registro de asistencia
 */
public record AbsenceRetracted(UUID recordId) {
}
