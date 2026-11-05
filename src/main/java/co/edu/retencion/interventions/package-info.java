/**
 * Módulo <b>interventions</b>: intervenciones de apoyo (tutoría, psicología, beca, reunión con padres,
 * flexibilización, ayuda económica...) con responsable, fecha límite, resultado y notas de
 * seguimiento con visibilidad por rol (las de psicología solo para PSICOLOGO). Al resolver una
 * intervención se reanaliza el riesgo y se guarda el antes/después para medir su efecto. También
 * convierte un plan de apoyo aprobado (y editado) por el consejero en intervenciones.
 *
 * <p>API pública: el evento {@link co.edu.retencion.interventions.InterventionAssigned}.
 *
 * <p>Asistido por IA. Ver BITACORA_IA.md.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Intervenciones")
package co.edu.retencion.interventions;
