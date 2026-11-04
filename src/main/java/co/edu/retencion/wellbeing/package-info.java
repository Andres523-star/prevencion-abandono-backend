/**
 * Bienestar: evaluaciones físicas y psicológicas y remisiones a servicios. Son datos sensibles
 * (Ley 1581 de 2012): texto cifrado en columna, acceso exclusivo del rol PSICOLOGO y cada lectura
 * queda en la auditoría con su motivo. A los demás módulos solo se expone {@link
 * co.edu.retencion.wellbeing.WellbeingSignals}: conteos sin contenido, para el motor de riesgo.
 *
 * <p>Asistido por IA (Claude, Anthropic).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Bienestar (datos sensibles)")
package co.edu.retencion.wellbeing;
