package co.edu.permanencia.demo.perfil;

import co.edu.permanencia.demo.auth.Usuario;
import co.edu.permanencia.demo.common.Alcance;
import co.edu.permanencia.demo.common.ApiException;
import co.edu.permanencia.demo.students.Estudiante;
import co.edu.permanencia.demo.students.EstudianteService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Estadísticas personales del estudiante: notas por período y comparación inicio vs. actual. */
@RestController
@RequestMapping("/api/estudiantes/{estudianteId}/perfil")
public class PerfilController {

    private final PerfilService perfiles;
    private final EstudianteService estudiantes;

    public PerfilController(PerfilService perfiles, EstudianteService estudiantes) {
        this.perfiles = perfiles;
        this.estudiantes = estudiantes;
    }

    @GetMapping
    public PerfilEstudiante perfil(@PathVariable int estudianteId,
            @RequestAttribute(name = "usuario", required = false) Usuario usuario) {
        Estudiante estudiante = estudiantes.porId(estudianteId);
        if (!Alcance.cubre(usuario, estudiante.id(), estudiante.carrera(), estudiante.facultad(),
                estudiante.sede(), estudiante.semestre())) {
            throw ApiException.forbidden("No tienes acceso a este perfil");
        }
        return perfiles.perfil(estudianteId);
    }
}
