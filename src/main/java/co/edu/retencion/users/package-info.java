/**
 * Módulo <b>users</b>: usuarios internos de la plataforma, sus roles y su alcance (programas).
 *
 * <p>API pública (este paquete): {@link co.edu.retencion.users.UserDirectory},
 * {@link co.edu.retencion.users.UserAccount}, {@link co.edu.retencion.users.FederatedIdentity}
 * y el evento {@link co.edu.retencion.users.UserProvisioned}. Ningún otro módulo accede a la
 * entidad ni al repositorio.
 *
 * <p>El correo y el nombre se guardan cifrados (AES-256-GCM) y el correo tiene blind index
 * para poder vincular la cuenta de Supabase sin descifrar.
 *
 * <p>Asistido por IA (Claude, Anthropic). Ver BITACORA_IA.md.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Usuarios")
package co.edu.retencion.users;
