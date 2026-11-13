package co.edu.permanencia.demo.reports;

import java.util.List;

/** Resumen estadístico que alimenta las gráficas y las tarjetas del dashboard. */
public record Reporte(
        int totalEstudiantes,
        int riesgoAlto,
        int riesgoMedio,
        int riesgoBajo,
        int retirados,
        int aplazados,
        int activos,
        double porcentajeDesercion,
        double promedioGeneral,
        int totalFaltas,
        String categoriaPrincipal,
        String insight,
        List<ConteoCarrera> porCarrera,
        List<ConteoCategoria> porCategoria,
        List<ConteoSemestre> porSemestre,
        List<ConteoSede> porSede,
        List<ConteoGrupo> porGrupo
) {

    public record ConteoCarrera(String carrera, int total, int alto, int medio, int bajo, int retirados,
                                int aplazados) {
    }

    public record ConteoCategoria(String categoria, int total, double porcentaje, int alto) {
    }

    public record ConteoSemestre(int semestre, int total, int alto, int medio, int bajo, int retirados) {
    }

    public record ConteoSede(String sede, int total, int alto, int medio, int bajo, int retirados) {
    }

    /** Conteo por grupo (A/B) para el filtro y el desglose de reportes. */
    public record ConteoGrupo(String grupo, int total, int alto, int medio, int bajo, int retirados) {
    }
}
