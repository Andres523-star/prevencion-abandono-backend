/**
 * Módulo <b>notifications</b>: la campanita, SOLO dentro de la app (nunca correo). Consume eventos de
 * riesgo, asistencia, justificaciones, alertas, chat, intervenciones y retención; entrega en tiempo
 * real en {@code /user/queue/notifications} y por REST (listado, no leídas, marcar leídas).
 *
 * <ul>
 *   <li>La tabla guarda el {@code studentId} y un motivo estructurado (códigos y valores académicos),
 *       nunca el nombre: el nombre se resuelve al leer, con ABAC y auditoría.</li>
 *   <li>Al docente solo le llegan motivos académicos (notas y faltas) de estudiantes de sus grupos;
 *       nunca factores psicológicos, de salud ni socioeconómicos.</li>
 *   <li>Deduplicación (mismo destinatario, tipo, estudiante, nivel y motivo dentro de
 *       {@code notifications.dedupe-hours}) y expiración ({@code notifications.expiry-days}).</li>
 * </ul>
 *
 * <p>Sin API pública: es un consumidor final de eventos.
 *
 * <p>Asistido por IA. Ver BITACORA_IA.md.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Campanita")
package co.edu.retencion.notifications;
