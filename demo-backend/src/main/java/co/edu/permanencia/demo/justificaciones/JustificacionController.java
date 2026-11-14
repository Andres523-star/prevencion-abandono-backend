package co.edu.permanencia.demo.justificaciones;

import co.edu.permanencia.demo.auth.Usuario;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Justificaciones de falta: el estudiante radica y consulta sus soportes. */
@RestController
@RequestMapping("/api/justificaciones")
public class JustificacionController {

    private final JustificacionService justificaciones;

    public JustificacionController(JustificacionService justificaciones) {
        this.justificaciones = justificaciones;
    }

    @GetMapping
    public List<Justificacion> listar(
            @RequestAttribute(name = "usuario", required = false) Usuario usuario) {
        return justificaciones.listar(usuario);
    }

    @GetMapping("/correo")
    public Map<String, String> correo(
            @RequestAttribute(name = "usuario", required = false) Usuario usuario) {
        return Map.of("correo", justificaciones.correoSugerido(usuario),
                "recordatorio", "Recuerda agregar la foto de evidencia antes de enviar.");
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Justificacion crear(@RequestBody JustificacionRequest request,
                               @RequestAttribute(name = "usuario", required = false) Usuario usuario) {
        return justificaciones.crear(usuario, request);
    }
}
