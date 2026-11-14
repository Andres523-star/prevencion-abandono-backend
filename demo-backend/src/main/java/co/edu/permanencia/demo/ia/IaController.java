package co.edu.permanencia.demo.ia;

import co.edu.permanencia.demo.auth.Usuario;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Mini chat de IA con alcance por jerarquía. */
@RestController
@RequestMapping("/api/ia")
public class IaController {

    private final IaService ia;

    public IaController(IaService ia) {
        this.ia = ia;
    }

    @PostMapping("/consultar")
    public RespuestaIA consultar(@RequestBody PreguntaIA pregunta,
                                 @RequestAttribute(name = "usuario", required = false) Usuario usuario) {
        return ia.responder(usuario, pregunta == null ? null : pregunta.mensaje());
    }

    @GetMapping("/capacidades")
    public Map<String, Object> capacidades(
            @RequestAttribute(name = "usuario", required = false) Usuario usuario) {
        if (usuario == null) {
            return Map.of("disponible", false, "rol", "SIN_SESION", "capacidades", List.of());
        }
        List<String> capacidades = switch (usuario.rol()) {
            case "RECTOR" -> List.of("Consultar todo el país", "Comparar sedes y programas",
                    "Ver grupos A/B", "Analizar faltas y materias", "Descargar reportes");
            case "DECANO" -> List.of("Consultar mi facultad en todas las sedes",
                    "Comparar programas y grupos de la facultad", "Ver grupos A/B",
                    "Analizar faltas y materias");
            case "COORDINADOR_SEDE" -> List.of("Consultar mi sede",
                    "Comparar programas de la sede", "Ver grupos A/B", "Analizar faltas y materias");
            case "JEFE_PROGRAMA" -> List.of("Consultar mi programa",
                    "Comparar semestres y grupos A/B", "Analizar faltas y materias");
            case "PROFESOR" -> List.of("Consultar mi programa y sede",
                    "Revisar los semestres que dicto", "Analizar faltas y materias");
            case "ESTUDIANTE" -> List.of("Conversación confidencial", "Revisar mi semestre",
                    "Entender mis faltas", "Preparar una justificación", "Apoyo emocional");
            default -> List.of();
        };
        return Map.of("disponible", true, "rol", usuario.rol(), "capacidades", capacidades);
    }

    @GetMapping("/historial")
    public List<MensajeIA> historial(
            @RequestAttribute(name = "usuario", required = false) Usuario usuario) {
        return ia.historial(usuario);
    }

    @DeleteMapping("/historial")
    public Map<String, Object> limpiarHistorial(
            @RequestAttribute(name = "usuario", required = false) Usuario usuario) {
        return Map.of("eliminados", ia.limpiarHistorial(usuario));
    }
}
