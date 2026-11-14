package co.edu.permanencia.demo.perfil;

import java.util.List;

/** Perfil académico y personal que ve el rol Estudiante en su pantalla de estadísticas. */
public record PerfilEstudiante(
        int estudianteId,
        String nombre,
        String apellido,
        String carrera,
        String facultad,
        String sede,
        int semestre,
        String grupo,
        String email,
        String fechaIngreso,
        String estado,
        String categoria,
        String razon,
        double promedioActual,
        int faltas,
        String riesgoActual,
        int riesgoScore,
        List<PeriodoAcademico> periodos,
        ResumenPersonal inicio,
        ResumenPersonal actual,
        String lectura
) {
}
