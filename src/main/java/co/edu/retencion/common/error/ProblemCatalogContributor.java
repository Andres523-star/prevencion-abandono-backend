package co.edu.retencion.common.error;

import java.util.List;

/**
 * Cada módulo publica sus propios prototipos de error implementando esta interfaz como bean.
 * El catálogo los recoge al arrancar, de modo que common no conoce los errores de negocio.
 */
public interface ProblemCatalogContributor {

    List<ApiProblem> problems();
}
