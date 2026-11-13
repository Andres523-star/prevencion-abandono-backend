package co.edu.permanencia.demo.risk;

import java.util.List;

/** Resultado del análisis que se muestra al profesor. */
public record AnalisisResponse(
        int estudianteId,
        String nombre,
        String riesgoActual,
        int riesgoScore,
        double promedio,
        int faltas,
        String categoria,
        String recomendacion,
        List<String> acciones,
        String fuente,
        String fecha
) {
}
