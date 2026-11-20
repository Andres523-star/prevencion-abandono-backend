package co.edu.permanencia.demo.auth;

import co.edu.permanencia.demo.students.EstudianteService;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catálogo de registro calculado una sola vez desde el directorio de estudiantes de la demo.
 *
 * <p>Recorrer el directorio por cada sede es costoso, así que el resultado se guarda la primera
 * vez que se pide (los datos de la demo no cambian de sede ni de programa).</p>
 */
@Component
class CatalogoRegistroEstudiantes implements CatalogoRegistro {

    private final EstudianteService estudiantes;
    private volatile Map<String, List<String>> cache;

    CatalogoRegistroEstudiantes(EstudianteService estudiantes) {
        this.estudiantes = estudiantes;
    }

    @Override
    public Map<String, List<String>> programasPorSede() {
        Map<String, List<String>> actual = cache;
        if (actual == null) {
            synchronized (this) {
                actual = cache;
                if (actual == null) {
                    Map<String, List<String>> valores = new LinkedHashMap<>();
                    for (String sede : estudiantes.sedes()) {
                        valores.put(sede, estudiantes.programasDe(sede));
                    }
                    // Orden estable (el de las sedes) para que el formulario las muestre igual siempre.
                    actual = Collections.unmodifiableMap(valores);
                    cache = actual;
                }
            }
        }
        return actual;
    }
}
