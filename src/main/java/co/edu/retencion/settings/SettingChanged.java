package co.edu.retencion.settings;

/**
 * Evento de dominio: un parámetro cambió. Lo consumen las cachés para invalidarse y, en fases
 * futuras, el motor de riesgo para recalcular con los nuevos umbrales.
 *
 * @param key clave modificada
 */
public record SettingChanged(String key) {
}
