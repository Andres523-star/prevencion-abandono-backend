package co.edu.permanencia.demo.acompanamiento;

import co.edu.permanencia.demo.auth.Usuario;
import co.edu.permanencia.demo.common.Alcance;
import co.edu.permanencia.demo.common.ApiException;
import co.edu.permanencia.demo.students.Estudiante;
import co.edu.permanencia.demo.students.EstudianteService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Historial de acompañamiento de un estudiante y cambio de estado de cada acción. */
@RestController
@RequestMapping("/api/estudiantes/{estudianteId}/acompanamiento")
public class AcompanamientoController {

    private final AcompanamientoService acompanamiento;
    private final EstudianteService estudiantes;

    public AcompanamientoController(AcompanamientoService acompanamiento, EstudianteService estudiantes) {
        this.acompanamiento = acompanamiento;
        this.estudiantes = estudiantes;
    }

    @GetMapping
    public List<AccionAcompanamiento> listar(@PathVariable int estudianteId,
            @RequestAttribute(name = "usuario", required = false) Usuario usuario) {
        validar(usuario, estudianteId);
        return acompanamiento.de(estudianteId);
    }

    @PutMapping("/{accionId}")
    public AccionAcompanamiento actualizar(@PathVariable int estudianteId, @PathVariable int accionId,
                                           @RequestBody EstadoRequest request,
                                           @RequestAttribute(name = "usuario", required = false) Usuario usuario) {
        validar(usuario, estudianteId);
        if (usuario != null && "ESTUDIANTE".equals(usuario.rol())) {
            throw ApiException.forbidden("El historial del estudiante es solo de lectura");
        }
        return acompanamiento.actualizar(estudianteId, accionId, request.estado());
    }

    private void validar(Usuario usuario, int estudianteId) {
        Estudiante estudiante = estudiantes.porId(estudianteId);
        if (!Alcance.cubre(usuario, estudiante.id(), estudiante.carrera(), estudiante.facultad(),
                estudiante.sede(), estudiante.semestre())) {
            throw ApiException.forbidden("No tienes acceso al historial de este estudiante");
        }
    }

    public record EstadoRequest(String estado) {
    }
}
