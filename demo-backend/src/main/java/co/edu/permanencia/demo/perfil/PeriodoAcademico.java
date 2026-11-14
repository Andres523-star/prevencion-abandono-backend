package co.edu.permanencia.demo.perfil;

import co.edu.permanencia.demo.students.NotaMateria;

import java.util.List;

/** Resultado académico de un período (semestre) del estudiante. */
public record PeriodoAcademico(
        String periodo,
        String etiqueta,
        double promedio,
        int faltas,
        String riesgo,
        int riesgoScore,
        List<NotaMateria> materias,
        String inicio,
        String fin
) {

    /** Constructor sin fechas; el servicio de perfil las calcula a partir del período. */
    public PeriodoAcademico(String periodo, String etiqueta, double promedio, int faltas,
                            String riesgo, int riesgoScore, List<NotaMateria> materias) {
        this(periodo, etiqueta, promedio, faltas, riesgo, riesgoScore, materias, "", "");
    }
}
