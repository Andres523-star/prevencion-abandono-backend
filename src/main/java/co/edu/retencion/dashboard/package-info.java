/**
 * Módulo <b>dashboard</b>: resumen de la pantalla inicial según el rol (totales, estudiantes en riesgo,
 * alertas abiertas, porcentaje de abandono, listas ALTO/MEDIO/BAJO paginadas y últimas notificaciones).
 * No guarda datos: compone lo que exponen analytics, risk, alerts, notifications y students.
 *
 * <p>Asistido por IA (Claude, Anthropic). Ver BITACORA_IA.md.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Tablero")
package co.edu.retencion.dashboard;
