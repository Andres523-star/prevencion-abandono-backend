/**
 * Módulo <b>students</b>: estudiante (enlazado por {@code anon_code}), identidad separada y
 * cifrada (nombre, documento con blind index, correo, teléfono), perfil socioeconómico sin
 * identificadores, inscripciones a grupos y consentimiento de tratamiento de datos.
 *
 * <p>API pública: {@link co.edu.retencion.students.StudentRegistry} (consulta y altas idempotentes
 * para importación) y los tipos que usa. Los DTO públicos nunca incluyen identidad; la identidad
 * solo se entrega a roles autorizados y cada lectura queda auditada.
 *
 * <p>Asistido por IA (Claude, Anthropic). Ver BITACORA_IA.md.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Estudiantes")
package co.edu.retencion.students;
