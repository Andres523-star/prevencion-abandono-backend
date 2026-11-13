package co.edu.permanencia.demo.risk;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Análisis de un estudiante con el plan de acción para el profesor. */
@RestController
@RequestMapping("/api/analizar")
public class AnalisisController {

    private final AnalisisService analisis;

    public AnalisisController(AnalisisService analisis) {
        this.analisis = analisis;
    }

    @PostMapping("/{estudianteId}")
    public AnalisisResponse analizar(@PathVariable int estudianteId) {
        return analisis.analizar(estudianteId);
    }
}
