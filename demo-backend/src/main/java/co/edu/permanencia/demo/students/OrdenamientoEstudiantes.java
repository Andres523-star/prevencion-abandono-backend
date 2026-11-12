package co.edu.permanencia.demo.students;

import java.util.Comparator;

public final class OrdenamientoEstudiantes {
    private OrdenamientoEstudiantes() {}

    public static Comparator<Estudiante> porRiesgoDescendente() {
        return Comparator.comparingInt(Estudiante::riesgo).reversed();
    }

    public static Comparator<Estudiante> porPromedioDescendente() {
        return Comparator.comparingDouble(Estudiante::promedio).reversed();
    }

    public static Comparator<Estudiante> porNombreAscendente() {
        return Comparator.comparing(Estudiante::nombre, String.CASE_INSENSITIVE_ORDER);
    }
}
