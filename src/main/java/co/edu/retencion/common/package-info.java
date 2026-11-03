/**
 * Módulo <b>common</b>: infraestructura transversal compartida por todos los módulos.
 *
 * <p>Es un módulo <em>abierto</em> ({@code Type.OPEN}): sus subpaquetes pueden usarse desde
 * cualquier otro módulo. Contiene solo piezas técnicas sin reglas de negocio:
 * <ul>
 *   <li>{@code id}: generación de UUID v7 (patrón Singleton).</li>
 *   <li>{@code error}: errores RFC 7807 con catálogo de prototipos (patrón Prototype).</li>
 *   <li>{@code security}: JWT de la plataforma, roles, RBAC + ABAC reutilizable.</li>
 *   <li>{@code crypto}: cifrado envolvente AES-256-GCM (Factory Method, Adapter, Proxy) y blind index.</li>
 *   <li>{@code state}: estado distribuido Redis/memoria (Abstract Factory, Adapter).</li>
 *   <li>{@code web}: request-id, IP del cliente, rate limiting, paginación por keyset.</li>
 * </ul>
 *
 * <p>Asistido por IA (Claude, Anthropic). Ver BITACORA_IA.md.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Común",
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package co.edu.retencion.common;
