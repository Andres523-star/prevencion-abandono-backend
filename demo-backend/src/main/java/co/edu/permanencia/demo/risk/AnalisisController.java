package co.edu.permanencia.demo.risk;

import co.edu.permanencia.demo.auth.Usuario;
import co.edu.permanencia.demo.common.Alcance;
import co.edu.permanencia.demo.common.ApiException;
import co.edu.permanencia.demo.students.Estudiante;
import co.edu.permanencia.demo.students.EstudianteService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Análisis de un estudiante con el plan de acción para el profesor.
 *
 * <p>Antes no revisaba el alcance: cualquier sesión podía pedir el análisis (con nombre y motivo)
 * de cualquier estudiante por su id. Ahora aplica la misma regla que la ficha del estudiante.</p>
 */
@RestController
@RequestMapping("/api/analizar")
public class AnalisisController {

    private final AnalisisService analisis;
    private final EstudianteService estudiantes;

    public AnalisisController(AnalisisService analisis, EstudianteService estudiantes) {
        this.analisis = analisis;
        this.estudiantes = estudiantes;
    }

    @PostMapping("/{estudianteId}")
    public AnalisisResponse analizar(@PathVariable int estudianteId,
                                     @RequestAttribute(name = "usuario", required = false) Usuario usuario) {
        Estudiante e = estudiantes.porId(estudianteId);
        if (usuario == null || !Alcance.cubre(usuario, e.id(), e.carrera(), e.facultad(), e.sede(), e.semestre())) {
            throw ApiException.forbidden("No tienes acceso a este estudiante");
        }
        return analisis.analizar(estudianteId);
    }
}
