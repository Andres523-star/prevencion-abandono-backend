package co.edu.permanencia.demo.auth;

import java.util.List;
import java.util.Map;

/**
 * Sedes y programas válidos para el registro público de profesores.
 *
 * <p>Es una interfaz para que {@link AuthService} no dependa del directorio de 1,1 millones de
 * estudiantes (y se pueda probar sin generarlo).</p>
 */
public interface CatalogoRegistro {

    /** Programas que se dictan en cada sede, con los nombres tal como aparecen en los datos. */
    Map<String, List<String>> programasPorSede();
}
