/**
 * Módulo <b>analytics</b>: indicadores agregados de deserción, riesgo, asistencia y retención para los
 * gráficos del frontend, con un conjunto común de filtros. Lee solo su modelo de lectura (vistas
 * materializadas {@code analytics_*}, definidas en la migración V6), que se refresca al recibir eventos
 * de dominio; las respuestas se cachean en Redis y se invalidan por generación.
 *
 * <p>Las respuestas son agregados con tamaño mínimo de celda ({@code analytics.min-cell-size}): un grupo
 * con menos estudiantes se suprime para evitar la reidentificación. Alcance por programa (ABAC).
 *
 * <p>API pública: {@link co.edu.retencion.analytics.AnalyticsQueries},
 * {@link co.edu.retencion.analytics.AnalyticsFilter}, {@link co.edu.retencion.analytics.AnalyticsViewer} y
 * {@link co.edu.retencion.analytics.DropoutHistoryBook} (series históricas).
 *
 * <p>Asistido por IA (Claude, Anthropic). Ver BITACORA_IA.md.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Analítica")
package co.edu.retencion.analytics;
