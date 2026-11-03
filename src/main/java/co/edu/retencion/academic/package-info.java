/**
 * Módulo <b>academic</b> (estructura académica, "academic-structure" en CLAUDE.md): programas,
 * periodos, festivos y suspensiones, materias con prerrequisitos, grupos (regulares y prácticas
 * clínicas), horarios, sesiones de clase generadas automáticamente y políticas de asistencia.
 *
 * <p>API pública (este paquete): {@link co.edu.retencion.academic.AcademicDirectory} (consultas),
 * {@link co.edu.retencion.academic.AcademicCatalog} (altas idempotentes para importación y datos
 * sintéticos), los records de información y el evento
 * {@link co.edu.retencion.academic.GroupTeacherChanged}. Implementa además las SPI de common
 * {@code TeachingAssignmentProvider} (ABAC del docente) y {@code ProgramReferences}.
 *
 * <p>Asistido por IA (Claude, Anthropic). Ver BITACORA_IA.md.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Estructura académica")
package co.edu.retencion.academic;
