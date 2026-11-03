/**
 * Módulo <b>imports</b>: importación masiva por CSV o Excel (estudiantes, docentes, materias,
 * grupos e inscripciones).
 *
 * <ul>
 *   <li>Validación fila por fila y modo <em>dry-run</em> (solo valida, no escribe).</li>
 *   <li>Archivos pequeños se procesan en la petición; los grandes quedan en cola y los procesa un
 *       worker ({@code FOR UPDATE SKIP LOCKED}, funciona con varias instancias).</li>
 *   <li>Idempotencia por clave natural: reimportar el mismo archivo no duplica nada.</li>
 *   <li>Reporte descargable por fila (sin valores, solo resultado y mensaje). El archivo, que
 *       contiene datos personales, se borra al terminar.</li>
 *   <li>Mapeo de columnas configurable: alias por campo en {@code app.imports.aliases} y
 *       sobrescritura por trabajo.</li>
 * </ul>
 *
 * <p>Solo usa las API públicas de academic, students y users. No expone API a otros módulos.
 *
 * <p>Asistido por IA (Claude, Anthropic). Ver BITACORA_IA.md.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Importación masiva")
package co.edu.retencion.imports;
