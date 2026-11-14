package co.edu.permanencia.demo.perfil;

/** Resumen de un momento académico para comparar el inicio con la situación actual. */
public record ResumenPersonal(
        String periodo,
        double promedio,
        int faltas,
        String riesgo,
        int riesgoScore
) {
}
