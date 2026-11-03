/**
 * Módulo <b>auth</b>: inicio de sesión y gestión de sesiones.
 *
 * <ul>
 *   <li>Supabase Auth como proveedor de identidad: cualquier correo (o Google, Microsoft, enlace
 *       mágico) con correo confirmado. El backend valida el token de Supabase y lo canjea una sola
 *       vez por la sesión propia.</li>
 *   <li>Sesión propia: JWT de acceso de 15 min + refresh token opaco rotativo con detección de
 *       reutilización (si se reusa un token ya rotado se revoca toda la familia).</li>
 *   <li>MFA obligatorio para ADMIN, CONSEJERO, PSICOLOGO y COORDINADOR (claim {@code aal = aal2}).</li>
 *   <li>Cuentas break-glass locales: argon2id + TOTP y bloqueo progresivo.</li>
 * </ul>
 *
 * <p>No expone API pública a otros módulos: solo endpoints REST.
 *
 * <p>Asistido por IA (Claude, Anthropic). Ver BITACORA_IA.md.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Autenticación")
package co.edu.retencion.auth;
