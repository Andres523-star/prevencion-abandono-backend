/**
 * Módulo <b>alerts</b>: alertas de riesgo para el personal. Se generan cuando el nivel de riesgo sube
 * o cruza un umbral, o ante un umbral de asistencia o la pérdida de una materia. Una sola alerta
 * abierta por estudiante y tipo (deduplicación), asignación automática a un consejero del programa o
 * a bienestar si hay factores psicológicos, y escalamiento a la coordinación si no se atiende en
 * {@code alerts.escalation-hours}.
 *
 * <p>La alerta lleva {@code anon_code}, programa, semestre, grupo, nivel, puntaje, factores y acciones
 * sugeridas; la identidad solo se resuelve con el endpoint de identidad (roles autorizados, motivo y
 * auditoría). API pública: el evento {@link co.edu.retencion.alerts.AlertAssigned}.
 *
 * <p>Asistido por IA. Ver BITACORA_IA.md.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Alertas")
package co.edu.retencion.alerts;
