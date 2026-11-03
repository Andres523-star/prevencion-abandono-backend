/**
 * Módulo <b>settings</b>: parámetros de negocio modificables en caliente (sin redeploy),
 * guardados en {@code app_settings} como JSON.
 *
 * <p>API pública: {@link co.edu.retencion.settings.SettingsProvider} (lectura tipada) y el evento
 * {@link co.edu.retencion.settings.SettingChanged}. La implementación expuesta es un proxy de caché
 * sobre la lectura JPA (patrón Proxy).
 *
 * <p>Asistido por IA (Claude, Anthropic). Ver BITACORA_IA.md.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Configuración")
package co.edu.retencion.settings;
