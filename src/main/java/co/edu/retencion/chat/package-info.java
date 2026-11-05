/**
 * Módulo <b>chat</b>: chat interno docente-estudiante en tiempo real (texto, audio, PDF y Word).
 *
 * <ul>
 *   <li>Un docente solo inicia chat con estudiantes inscritos en sus grupos del periodo activo; el
 *       estudiante responde; nadie más lee salvo ADMIN con motivo registrado en la auditoría.</li>
 *   <li>Contenido y nombres de archivo cifrados en reposo; retención configurable
 *       ({@code chat.retention-days}); los mensajes y adjuntos nunca salen hacia la IA ni a los logs
 *       (los eventos solo llevan ids).</li>
 *   <li>Adjuntos validados por magic bytes (PDF, .docx sin macros, audio webm/ogg/mp3/m4a), tamaño y
 *       duración máximos, y antivirus antes de quedar disponibles; descarga solo con URL prefirmada
 *       de corta duración para participantes, auditada.</li>
 *   <li>Conversación con tabla de participantes: se podrá sumar consejería o bienestar sin migración
 *       grande.</li>
 * </ul>
 *
 * <p>API pública: el evento {@link co.edu.retencion.chat.ChatMessageSent}.
 *
 * <p>Asistido por IA. Ver BITACORA_IA.md.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Chat")
package co.edu.retencion.chat;
