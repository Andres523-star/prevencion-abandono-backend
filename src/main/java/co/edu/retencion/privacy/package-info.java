/**
 * Módulo <b>privacy</b>: derechos del titular y cumplimiento de la Ley 1581 de 2012. Solicitudes de consulta
 * (copia de sus datos), rectificación y supresión con plazos legales en días hábiles, aprobación y
 * trazabilidad; registro de actividades de tratamiento; y vencimiento de las copias entregadas.
 *
 * <p>La supresión es el proceso formal que exige la plataforma para borrar datos personales: borra la
 * identidad cifrada y lo que no tiene obligación legal de conservarse, y avisa a los demás módulos con el
 * evento {@link co.edu.retencion.privacy.StudentDataSuppressed}.
 *
 * <p>API pública: los eventos {@link co.edu.retencion.privacy.DataRequestSubmitted},
 * {@link co.edu.retencion.privacy.DataRequestAnswered} y {@link co.edu.retencion.privacy.StudentDataSuppressed}.
 *
 * <p>Asistido por IA (Claude, Anthropic). Ver BITACORA_IA.md.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Privacidad y derechos del titular")
package co.edu.retencion.privacy;
