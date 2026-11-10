/**
 * Módulo <b>portal</b>: API del portal del estudiante. Reúne en pocas pantallas lo que el estudiante necesita
 * de sí mismo (materias, faltas con la política que aplica, notas, historial de faltas y el apoyo que la
 * universidad le ofrece). Solo lectura y siempre de sus propios datos: el estudiante se resuelve desde su
 * usuario, nunca desde un parámetro.
 *
 * <p>Las acciones del portal viven en sus módulos y se documentan en {@code docs/portal-del-estudiante.md}:
 * justificar y apelar (justifications), "necesito ayuda" (retention), campanita (notifications), chat (chat),
 * consentimientos (students) y solicitudes sobre sus datos (privacy).
 *
 * <p>El portal nunca muestra nivel de riesgo, puntaje, probabilidad ni factores: el estudiante ve el apoyo
 * que una persona ya aprobó para él, en lenguaje comprensivo.
 *
 * <p>Sin API pública.
 *
 * <p>Asistido por IA (Claude, Anthropic). Ver BITACORA_IA.md.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Portal del estudiante")
package co.edu.retencion.portal;
