/**
 * Módulo <b>audit</b>: bitácora append-only de escrituras y lecturas sensibles.
 *
 * <p>API pública (este paquete): anotaciones {@link co.edu.retencion.audit.Audited} y
 * {@link co.edu.retencion.audit.SensitiveRead}, e interfaz {@link co.edu.retencion.audit.AuditTrail}
 * para registrar eventos de forma programática. Todo lo demás (subpaquetes) es interno.
 *
 * <p>Garantías: la tabla {@code audit_log} está particionada por mes, el rol de la aplicación no
 * tiene UPDATE/DELETE y un trigger rechaza cualquier modificación; cada fila guarda el hash de
 * la anterior (cadena SHA-256) para detectar manipulación.
 *
 * <p>Asistido por IA (Claude, Anthropic). Ver BITACORA_IA.md.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Auditoría")
package co.edu.retencion.audit;
