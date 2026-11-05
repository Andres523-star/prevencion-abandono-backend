/**
 * Módulo <b>retention</b>: casos de retención ({@code retention_case}). Se abren por detección
 * automática (riesgo ALTO), por autorreporte del estudiante ("estoy pensando en retirarme") o por un
 * consejero, y avanzan DETECTADO → CONTACTADO → ENTREVISTA → PLAN_DE_APOYO → SEGUIMIENTO → cierre
 * (RETENIDO, APLAZADO, TRANSFERIDO o RETIRADO con causa y encuesta de salida). Seguimiento programado
 * a 7, 15 y 30 días con recordatorios, campaña de reingreso que exige aprobación y métricas del
 * embudo por programa y periodo.
 *
 * <p>API pública: el evento {@link co.edu.retencion.retention.RetentionFollowUpDue}.
 *
 * <p>Asistido por IA. Ver BITACORA_IA.md.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Retención")
package co.edu.retencion.retention;
