package co.edu.permanencia.demo.students;

import java.util.List;

/**
 * Estudiante de prueba.
 *
 * <p>Los datos son ficticios y viven en memoria. La cédula es única y el riesgo se calcula al
 * generar el registro; el historial permite ver la evolución en la ficha.</p>
 */
public record Estudiante(
        int id,
        String nombre,
        String apellido,
        String cedula,
        String carrera,
        String facultad,
        String sede,
        int semestre,
        String grupo,
        String email,
        String telefono,
        String categoria,
        String razon,
        double promedio,
        int faltas,
        String riesgoActual,
        int riesgoScore,
        String estado,
        String fechaIngreso,
        List<NotaMateria> materias,
        List<Integer> historialRiesgo
) {
}
