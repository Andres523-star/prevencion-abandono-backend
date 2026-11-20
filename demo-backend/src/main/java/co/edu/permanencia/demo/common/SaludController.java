package co.edu.permanencia.demo.common;

import co.edu.permanencia.demo.students.EstudianteService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Endpoint público para saber si el backend está arriba (lo usan el script de arranque y el healthcheck).
 *
 * <p>Con {@code DEMO_DEBUG_CLIENT_IP=true} añade la IP con la que el servidor identifica a quien llama y las
 * cabeceras de reenvío que trajo la petición. Sirve para comprobar, detrás del proxy del hosting, que los
 * límites de intentos usan la IP real del cliente. Solo devuelve datos de la propia petición; va apagado por
 * defecto y debe volver a apagarse después de la comprobación.</p>
 */
@RestController
@RequestMapping("/api/salud")
public class SaludController {

    private final EstudianteService estudiantes;
    private final boolean diagnosticoIp;

    public SaludController(EstudianteService estudiantes,
                           @Value("${demo.debug.client-ip:false}") boolean diagnosticoIp) {
        this.estudiantes = estudiantes;
        this.diagnosticoIp = diagnosticoIp;
    }

    @GetMapping
    public Map<String, Object> salud(HttpServletRequest request) {
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("estado", "ok");
        respuesta.put("estudiantes", estudiantes.todos().size());
        if (diagnosticoIp) {
            respuesta.put("ipCliente", request.getRemoteAddr());
            respuesta.put("seguro", request.isSecure());
            respuesta.put("xForwardedFor", String.valueOf(request.getHeader("X-Forwarded-For")));
            respuesta.put("xRealIp", String.valueOf(request.getHeader("X-Real-IP")));
        }
        return respuesta;
    }
}
