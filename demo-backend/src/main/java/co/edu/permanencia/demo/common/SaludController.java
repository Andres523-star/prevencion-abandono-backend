package co.edu.permanencia.demo.common;

import co.edu.permanencia.demo.students.EstudianteService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Endpoint público para saber si el backend está arriba (lo usa el script de arranque). */
@RestController
@RequestMapping("/api/salud")
public class SaludController {

    private final EstudianteService estudiantes;

    public SaludController(EstudianteService estudiantes) {
        this.estudiantes = estudiantes;
    }

    @GetMapping
    public Map<String, Object> salud() {
        return Map.of("estado", "ok", "estudiantes", estudiantes.todos().size());
    }
}
