/**
 * Módulo <b>justifications</b> ("attendance-justification" en CLAUDE.md): el estudiante justifica
 * una falta desde el portal con evidencias (PDF/JPG/PNG validadas por magic bytes y antivirus) y
 * SOLO el docente de la materia la aprueba (se descuenta del contador) o la rechaza con
 * comentario obligatorio. No hay aprobación automática ni de otro rol. Las faltas no justificadas
 * a tiempo vencen.
 *
 * <p>API pública: el evento {@link co.edu.retencion.justifications.JustificationDecided}.
 *
 * <p>Asistido por IA (Claude, Anthropic). Ver BITACORA_IA.md.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Justificaciones de faltas")
package co.edu.retencion.justifications;
