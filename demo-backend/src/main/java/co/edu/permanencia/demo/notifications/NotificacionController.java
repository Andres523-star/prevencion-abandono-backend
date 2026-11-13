package co.edu.permanencia.demo.notifications;

import co.edu.permanencia.demo.auth.Usuario;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Campanita personalizada: cada usuario recibe y marca sus propias notificaciones. */
@RestController
@RequestMapping("/api/notificaciones")
public class NotificacionController {

    private final NotificacionService notificaciones;

    public NotificacionController(NotificacionService notificaciones) {
        this.notificaciones = notificaciones;
    }

    @GetMapping
    public List<Notificacion> listar(@RequestParam(required = false) String grupo,
                                     @RequestAttribute(name = "usuario", required = false) Usuario usuario) {
        return notificaciones.listar(usuario, grupo);
    }

    @GetMapping("/resumen")
    public Map<String, Long> resumen(
            @RequestAttribute(name = "usuario", required = false) Usuario usuario) {
        return Map.of("noLeidas", notificaciones.noLeidas(usuario));
    }

    @PostMapping("/{id}/leer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leer(@PathVariable int id,
                     @RequestAttribute(name = "usuario", required = false) Usuario usuario) {
        notificaciones.marcarLeida(usuario, id);
    }

    @PostMapping("/leer-todas")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leerTodas(@RequestAttribute(name = "usuario", required = false) Usuario usuario) {
        notificaciones.marcarTodas(usuario);
    }
}
