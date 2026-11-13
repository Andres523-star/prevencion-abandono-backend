package co.edu.permanencia.demo.reports;

import co.edu.permanencia.demo.students.Estudiante;
import co.edu.permanencia.demo.students.EstudianteService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.IntStream;

/** Calcula los agregados que se muestran en el dashboard y en la página de estadísticas. */
@Service
public class ReporteService {

    private final EstudianteService estudiantes;

    public ReporteService(EstudianteService estudiantes) {
        this.estudiantes = estudiantes;
    }

    public Reporte generar(String carrera, String sede, Integer semestre, String categoria, String riesgo) {
        return generar(carrera, sede, null, semestre, null, categoria, riesgo, null);
    }

    public Reporte generar(String carrera, String sede, String facultad, Integer semestre,
                           List<Integer> semestres, String categoria, String riesgo) {
        return generar(carrera, sede, facultad, semestre, semestres, categoria, riesgo, null);
    }

    /**
     * Recorre una sola vez la población filtrada y acumula todos los agregados. Con un millón de
     * estudiantes esto evita crear una sublista por programa, sede, semestre, grupo y causa.
     */
    public Reporte generar(String carrera, String sede, String facultad, Integer semestre,
                           List<Integer> semestres, String categoria, String riesgo, String grupo) {
        Bucket global = new Bucket();
        double[] sumaPromedio = {0};
        Map<String, Bucket> carreras = new HashMap<>();
        Map<String, Bucket> categorias = new HashMap<>();
        Map<Integer, Bucket> semestresMap = new HashMap<>();
        Map<String, Bucket> sedes = new HashMap<>();
        Map<String, Bucket> grupos = new HashMap<>();

        estudiantes.streamFiltrado(null, carrera, sede, facultad, semestre, semestres, categoria,
                        riesgo, null, grupo)
                .forEach(e -> {
                    global.add(e);
                    sumaPromedio[0] += e.promedio();
                    carreras.computeIfAbsent(e.carrera(), clave -> new Bucket()).add(e);
                    categorias.computeIfAbsent(e.categoria(), clave -> new Bucket()).add(e);
                    semestresMap.computeIfAbsent(e.semestre(), clave -> new Bucket()).add(e);
                    sedes.computeIfAbsent(e.sede(), clave -> new Bucket()).add(e);
                    grupos.computeIfAbsent(e.grupo(), clave -> new Bucket()).add(e);
                });

        int total = global.total;
        double promedio = total == 0 ? 0 : sumaPromedio[0] / total;

        List<Reporte.ConteoCarrera> porCarrera = porCarrera(carreras, carrera);
        List<Reporte.ConteoCategoria> porCategoria = porCategoria(categorias, total);
        List<Reporte.ConteoSemestre> porSemestre = porSemestre(semestresMap, semestres);
        List<Reporte.ConteoSede> porSede = porSede(sedes);
        List<Reporte.ConteoGrupo> porGrupo = porGrupo(grupos);

        String categoriaPrincipal = porCategoria.isEmpty()
                ? "Sin datos" : porCategoria.getFirst().categoria();
        String insight = construirInsight(porCarrera, porCategoria, total);

        return new Reporte(total, global.alto, global.medio, global.bajo, global.retirados,
                global.aplazados, global.activos,
                redondear(total == 0 ? 0 : global.retirados * 100.0 / total),
                redondear(promedio), global.faltas, categoriaPrincipal, insight,
                porCarrera, porCategoria, porSemestre, porSede, porGrupo);
    }

    /** Compatibilidad con las llamadas anteriores. */
    public Reporte generar(String carrera, Integer semestre, String categoria) {
        return generar(carrera, null, semestre, categoria, null);
    }

    private List<Reporte.ConteoCarrera> porCarrera(Map<String, Bucket> carreras, String carreraFiltro) {
        List<Reporte.ConteoCarrera> resultado = new ArrayList<>();
        for (Map.Entry<String, Bucket> entrada : carreras.entrySet()) {
            Bucket b = entrada.getValue();
            resultado.add(new Reporte.ConteoCarrera(entrada.getKey(), b.total, b.alto, b.medio,
                    b.bajo, b.retirados, b.aplazados));
        }
        if (resultado.isEmpty() && carreraFiltro != null && !carreraFiltro.isBlank()) {
            resultado.add(new Reporte.ConteoCarrera(carreraFiltro, 0, 0, 0, 0, 0, 0));
        }
        resultado.sort(Comparator.comparingInt(Reporte.ConteoCarrera::alto).reversed()
                .thenComparing(Reporte.ConteoCarrera::carrera));
        return resultado;
    }

    private List<Reporte.ConteoCategoria> porCategoria(Map<String, Bucket> categorias, int total) {
        List<Reporte.ConteoCategoria> resultado = new ArrayList<>();
        for (String categoria : EstudianteService.CATEGORIAS) {
            Bucket b = categorias.get(categoria);
            if (b == null || b.total == 0) {
                continue;
            }
            double porcentaje = total == 0 ? 0 : redondear(b.total * 100.0 / total);
            resultado.add(new Reporte.ConteoCategoria(categoria, b.total, porcentaje, b.alto));
        }
        resultado.sort(Comparator.comparingInt(Reporte.ConteoCategoria::total).reversed()
                .thenComparing(Reporte.ConteoCategoria::categoria));
        return resultado;
    }

    private List<Reporte.ConteoSemestre> porSemestre(Map<Integer, Bucket> semestres,
                                                     List<Integer> permitidos) {
        List<Integer> lista = permitidos == null || permitidos.isEmpty()
                ? IntStream.rangeClosed(1, 10).boxed().toList()
                : permitidos;
        List<Reporte.ConteoSemestre> resultado = new ArrayList<>();
        for (int semestre : lista) {
            Bucket b = semestres.getOrDefault(semestre, new Bucket());
            resultado.add(new Reporte.ConteoSemestre(semestre, b.total, b.alto, b.medio, b.bajo,
                    b.retirados));
        }
        return resultado;
    }

    private List<Reporte.ConteoSede> porSede(Map<String, Bucket> sedes) {
        List<Reporte.ConteoSede> resultado = new ArrayList<>();
        for (Map.Entry<String, Bucket> entrada : sedes.entrySet()) {
            Bucket b = entrada.getValue();
            resultado.add(new Reporte.ConteoSede(entrada.getKey(), b.total, b.alto, b.medio,
                    b.bajo, b.retirados));
        }
        resultado.sort(Comparator.comparingInt(Reporte.ConteoSede::alto).reversed()
                .thenComparing(Reporte.ConteoSede::sede));
        return resultado;
    }

    private List<Reporte.ConteoGrupo> porGrupo(Map<String, Bucket> grupos) {
        List<Reporte.ConteoGrupo> resultado = new ArrayList<>();
        for (String grupo : EstudianteService.GRUPOS) {
            Bucket b = grupos.getOrDefault(grupo, new Bucket());
            resultado.add(new Reporte.ConteoGrupo(grupo, b.total, b.alto, b.medio, b.bajo,
                    b.retirados));
        }
        return resultado;
    }

    private String construirInsight(List<Reporte.ConteoCarrera> porCarrera,
                                    List<Reporte.ConteoCategoria> porCategoria, int total) {
        if (total == 0) {
            return "No hay estudiantes con los filtros seleccionados. Prueba con otro programa, semestre o causa.";
        }
        Reporte.ConteoCarrera carreraCritica = porCarrera.stream()
                .max(Comparator.comparingInt(Reporte.ConteoCarrera::alto)
                        .thenComparingInt(Reporte.ConteoCarrera::total))
                .orElse(null);
        Reporte.ConteoCategoria causaPrincipal = porCategoria.stream()
                .max(Comparator.comparingInt(Reporte.ConteoCategoria::total))
                .orElse(null);
        if (carreraCritica == null || causaPrincipal == null) {
            return "Los datos no son suficientes para generar una recomendación.";
        }
        double porcentaje = carreraCritica.total() == 0 ? 0
                : redondear(carreraCritica.alto() * 100.0 / carreraCritica.total());
        String recomendacion = switch (causaPrincipal.categoria()) {
            case "Crisis económica" -> "priorizar becas, matrícula cero y apoyos de transporte para ese programa.";
            case "Depresión familiar" -> "activar acompañamiento psicológico y un contacto cuidadoso con la familia.";
            case "Desconexión al futuro" -> "ofrecer tutorías vocacionales y encuentros con egresados del programa.";
            case "Estrés académico" -> "reforzar tutorías académicas y talleres de hábitos de estudio.";
            default -> "impulsar actividades prácticas, mentorías y un seguimiento cercano con el estudiante.";
        };
        return String.format(Locale.US,
                "El %.1f%% de los estudiantes de %s está en riesgo alto. La causa que más pesa es %s. "
                        + "Recomendación: %s", porcentaje, carreraCritica.carrera(),
                causaPrincipal.categoria().toLowerCase(Locale.ROOT), recomendacion);
    }

    private static double redondear(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /** Acumulador compacto por dimensión. */
    private static final class Bucket {
        int total;
        int alto;
        int medio;
        int bajo;
        int retirados;
        int aplazados;
        int activos;
        int faltas;

        void add(Estudiante e) {
            total++;
            faltas += e.faltas();
            switch (e.riesgoActual()) {
                case "ALTO" -> alto++;
                case "MEDIO" -> medio++;
                case "BAJO" -> bajo++;
                default -> {
                }
            }
            switch (e.estado()) {
                case "RETIRADO" -> retirados++;
                case "APLAZADO" -> aplazados++;
                case "ACTIVO" -> activos++;
                default -> {
                }
            }
        }
    }
}
