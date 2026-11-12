package co.edu.permanencia.demo.students;

/** Datos para crear un estudiante desde el formulario del frontend. */
public record EstudianteRequest(
        String nombre,
        String apellido,
        String cedula,
        String carrera,
        Integer semestre,
        String grupo,
        String email,
        String telefono,
        String categoria,
        String razon,
        Double promedio,
        Integer faltas,
        String estado
) {
}
