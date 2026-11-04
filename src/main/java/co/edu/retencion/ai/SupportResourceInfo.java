package co.edu.retencion.ai;

/**
 * Recurso de apoyo del catálogo de la universidad.
 *
 * @param id              código estable (lo que la IA debe citar)
 * @param name            nombre
 * @param category        categoría
 * @param description     descripción
 * @param eligibilityText elegibilidad en lenguaje natural
 * @param contact         contacto institucional (dependencia, extensión)
 */
public record SupportResourceInfo(String id, String name, String category, String description,
                                  String eligibilityText, String contact) {
}
