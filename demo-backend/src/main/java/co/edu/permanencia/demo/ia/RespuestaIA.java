package co.edu.permanencia.demo.ia;

import java.util.List;

/** Respuesta del asistente de IA de la demo. */
public record RespuestaIA(
        String respuesta,
        List<String> sugerencias,
        String alcance,
        boolean permitido
) {
}
