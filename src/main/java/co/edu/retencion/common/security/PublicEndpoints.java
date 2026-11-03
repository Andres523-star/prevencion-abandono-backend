package co.edu.retencion.common.security;

import java.util.List;

/**
 * Rutas que un módulo declara como públicas (sin JWT). Cada módulo publica un bean de este tipo
 * y la configuración de seguridad los combina; así common no conoce las rutas de los demás.
 *
 * @param patterns patrones Ant de ruta, p. ej. {@code /api/v1/auth/refresh}
 */
public record PublicEndpoints(List<String> patterns) {
}
