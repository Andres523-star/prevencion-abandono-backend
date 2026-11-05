package co.edu.retencion.alerts;

import java.util.UUID;

/**
 * Evento: una alerta quedó asignada (nueva o escalada). La campanita avisa al responsable.
 *
 * @param alertId   alerta
 * @param studentId estudiante
 * @param recipient usuario asignado
 * @param level     nivel de riesgo (BAJO, MEDIO, ALTO)
 * @param kind      RIESGO, ASISTENCIA o PERDIDA
 * @param escalated {@code true} si es un escalamiento por falta de atención
 */
public record AlertAssigned(UUID alertId, UUID studentId, UUID recipient, String level, String kind,
                            boolean escalated) {
}
