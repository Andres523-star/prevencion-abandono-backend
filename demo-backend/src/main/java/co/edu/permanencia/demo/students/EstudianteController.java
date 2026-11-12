package co.edu.permanencia.demo.students;

import co.edu.permanencia.demo.auth.Usuario;
import co.edu.permanencia.demo.common.Alcance;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** API de estudiantes: listado con filtros, ficha y creación. */
@RestController
@RequestMapping("/api/estudiantes")
public class EstudianteController {

    private final EstudianteService estudiantes;

    public EstudianteController(EstudianteService estudiantes) {
        this.estudiantes = estudiantes;
    }

    @GetMapping
    public List<Estudiante> listar(@RequestParam(required = false) String search,
                                   @RequestParam(required = false) String carrera,
                                   @RequestParam(required = false) String sede,
                                   @RequestParam(required = false) String facultad,
                                   @RequestParam(required = false) Integer semestre,
                                   @RequestParam(required = false) String categoria,
                                   @RequestParam(required = false) String riesgo,
                                   @RequestParam(required = false) String estado,
                                   @RequestParam(required = false) String grupo,
                                   @RequestParam(required = false) Integer limit,
                                   @RequestAttribute(name = "usuario", required = false) Usuario usuario) {
        if (usuario != null && "ESTUDIANTE".equals(usuario.rol())) {
            Integer propio = usuario.estudianteId();
            return propio == null ? List.of() : List.of(estudiantes.porId(propio));
        }
        String carreraPermitida = Alcance.carrera(usuario, carrera);
        String sedePermitida = Alcance.sede(usuario, sede);
        String facultadPermitida = Alcance.facultad(usuario, facultad);
        return estudiantes.listar(search, carreraPermitida, sedePermitida, facultadPermitida, semestre,
                Alcance.semestres(usuario, semestre), categoria, riesgo, estado, grupo, limit);
    }

    @GetMapping("/{id}")
    public Estudiante obtener(@PathVariable int id,
                              @RequestAttribute(name = "usuario", required = false) Usuario usuario) {
        Estudiante estudiante = estudiantes.porId(id);
        if (!Alcance.cubre(usuario, estudiante.id(), estudiante.carrera(), estudiante.facultad(),
                estudiante.sede(), estudiante.semestre())) {
            throw co.edu.permanencia.demo.common.ApiException.forbidden(
                    "No tienes acceso a la ficha de este estudiante");
        }
        return estudiante;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Estudiante crear(@RequestBody EstudianteRequest request) {
        return estudiantes.crear(request);
    }
}
