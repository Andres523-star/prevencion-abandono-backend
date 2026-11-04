/**
 * Módulo de correo: avisos de faltas al estudiante. Es el ÚNICO uso de correo de la plataforma
 * (las notificaciones al personal son internas, en la campanita).
 *
 * <p>Flujo: los eventos {@code AbsenceRecorded}, {@code AbsenceRetracted} y
 * {@code JustificationDecided} se convierten en filas de un outbox transaccional
 * ({@code email_outbox}). Un despachador las envía cuando vence la ventana de edición del docente
 * (o en un resumen diario), con reintentos con backoff y registro de entrega. La dirección se
 * resuelve al enviar (no se copia en el outbox) y el contenido se limita a hechos académicos:
 * materia, fecha, contador, límite, plazo para justificar y enlace al portal. Nunca riesgo, salud
 * ni información psicológica.
 *
 * <p>Patrones: Adapter ({@code GraphEmailSender}, {@code ResendEmailSender}) sobre el puerto
 * {@code EmailSender} y Factory Method para elegir el proveedor según la configuración.
 *
 * <p>Asistido por IA (Claude, Anthropic).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Correo de avisos al estudiante")
package co.edu.retencion.mailing;
