/**
 * Módulo <b>reports</b>: reportes asíncronos en PDF (OpenPDF) y Excel (Apache POI, SXSSF en streaming).
 * Se piden con {@code POST /reports}, se generan en segundo plano con el alcance que tenía quien los
 * pidió y se descargan con URL prefirmada mientras no venzan. Todos llevan encabezado de
 * confidencialidad; los identificables exigen motivo y quedan en la auditoría.
 *
 * <p>Sin API pública.
 *
 * <p>Asistido por IA (Claude, Anthropic). Ver BITACORA_IA.md.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Reportes")
package co.edu.retencion.reports;
