package co.edu.permanencia.demo.perfil;

import co.edu.permanencia.demo.students.Estudiante;
import co.edu.permanencia.demo.students.EstudianteService;
import co.edu.permanencia.demo.students.NotaMateria;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Construye el perfil académico que se muestra en la pantalla personal del estudiante. */
@Service
public class PerfilService {

    private final EstudianteService estudiantes;

    public PerfilService(EstudianteService estudiantes) {
        this.estudiantes = estudiantes;
    }

    public PerfilEstudiante perfil(int estudianteId) {
        Estudiante estudiante = estudiantes.porId(estudianteId);
        List<PeriodoAcademico> base = estudianteId == EstudianteService.ESTUDIANTE_DEMO_ID
                ? periodosDemo() : periodosGenericos(estudiante);
        List<PeriodoAcademico> periodos = conFechas(aplicarJustificaciones(estudianteId, base));
        PeriodoAcademico primero = periodos.getFirst();
        PeriodoAcademico ultimo = periodos.getLast();
        ResumenPersonal inicio = new ResumenPersonal(primero.periodo(), primero.promedio(),
                primero.faltas(), primero.riesgo(), primero.riesgoScore());
        ResumenPersonal actual = new ResumenPersonal(ultimo.periodo(), ultimo.promedio(),
                ultimo.faltas(), ultimo.riesgo(), ultimo.riesgoScore());
        return new PerfilEstudiante(estudiante.id(), estudiante.nombre(), estudiante.apellido(),
                estudiante.carrera(), estudiante.facultad(), estudiante.sede(), estudiante.semestre(),
                estudiante.grupo(), estudiante.email(), estudiante.fechaIngreso(), estudiante.estado(),
                estudiante.categoria(),
                estudiante.razon(), estudiante.promedio(), estudiante.faltas(),
                estudiante.riesgoActual(), estudiante.riesgoScore(), periodos, inicio, actual,
                lectura(inicio, actual));
    }

    private List<PeriodoAcademico> periodosDemo() {
        return List.of(
                new PeriodoAcademico("2024-2", "Primer semestre", 2.6, 9, "ALTO", 91, List.of(
                        m("Cálculo Diferencial", 2.1, 3, 1, 1, "A", false),
                        m("Introducción a la Ingeniería", 2.7, 2, 1, 1, "A", false),
                        m("Lógica de Programación", 2.8, 2, 1, 1, "A", false),
                        m("Competencias Comunicativas", 2.9, 2, 0, 1, "A", false))),
                new PeriodoAcademico("2025-1", "Segundo semestre", 2.8, 8, "ALTO", 86, List.of(
                        m("Cálculo Integral", 2.3, 3, 1, 2, "A", false),
                        m("Programación Orientada a Objetos", 2.8, 2, 1, 2, "A", false),
                        m("Bases de Datos I", 3.0, 2, 1, 2, "A", false),
                        m("Inglés I", 3.1, 1, 0, 2, "A", false))),
                new PeriodoAcademico("2025-2", "Tercer semestre", 3.2, 6, "ALTO", 74, List.of(
                        m("Estructuras de Datos", 2.9, 2, 1, 3, "A", false),
                        m("Bases de Datos II", 3.1, 2, 1, 3, "A", false),
                        m("Arquitectura de Computadores", 3.4, 1, 0, 3, "A", false),
                        m("Inglés II", 3.4, 1, 0, 3, "A", false))),
                new PeriodoAcademico("2026-1", "Cuarto semestre", 3.6, 3, "MEDIO", 58, List.of(
                        m("Análisis de Algoritmos", 3.5, 1, 0, 4, "A", false),
                        m("Ingeniería de Software I", 3.7, 1, 0, 4, "A", false),
                        m("Estadística", 3.6, 1, 0, 4, "A", false),
                        m("Sistemas Operativos", 3.6, 0, 0, 4, "A", false))),
                new PeriodoAcademico("2026-2", "Quinto semestre", 3.9, 2, "MEDIO", 47, List.of(
                        m("Ingeniería de Software II", 4.2, 1, 1, 5, "A", false),
                        m("Redes de Computadores", 3.9, 1, 1, 5, "A", false),
                        m("Arquitectura de Software", 4.1, 0, 0, 5, "A", false),
                        m("Gestión de Proyectos", 3.8, 0, 0, 5, "A", false),
                        m("Bases de Datos II", 4.0, 0, 0, 3, "B", true))));
    }

    private static NotaMateria m(String nombre, double nota, int faltas, int justificadas,
                                 int semestre, String grupo, boolean homologada) {
        return new NotaMateria(nombre, nota, faltas, justificadas, semestre, grupo, homologada);
    }

    /**
     * Suma al perfil las justificaciones radicadas desde el portal. El conteo vive en
     * {@link EstudianteService} para que se mantenga aunque la materia sea de un período anterior.
     */
    private List<PeriodoAcademico> aplicarJustificaciones(int estudianteId, List<PeriodoAcademico> periodos) {
        List<PeriodoAcademico> salida = new ArrayList<>();
        for (PeriodoAcademico periodo : periodos) {
            List<NotaMateria> materias = new ArrayList<>();
            for (NotaMateria materia : periodo.materias()) {
                int extra = estudiantes.justificadasExtra(estudianteId, materia.semestre(), materia.nombre());
                int justificadas = Math.min(materia.faltas(), materia.faltasJustificadas() + extra);
                materias.add(justificadas == materia.faltasJustificadas() ? materia
                        : new NotaMateria(materia.nombre(), materia.nota(), materia.faltas(), justificadas,
                        materia.semestre(), materia.grupo(), materia.homologada(), materia.fecha()));
            }
            salida.add(new PeriodoAcademico(periodo.periodo(), periodo.etiqueta(), periodo.promedio(),
                    periodo.faltas(), periodo.riesgo(), periodo.riesgoScore(), List.copyOf(materias)));
        }
        return List.copyOf(salida);
    }

    /**
     * Completa las fechas del período y de cada nota. El estudiante ve el rango del semestre y la
     * fecha exacta del corte en el que se registró cada calificación.
     */
    private List<PeriodoAcademico> conFechas(List<PeriodoAcademico> periodos) {
        List<PeriodoAcademico> salida = new ArrayList<>();
        for (int p = 0; p < periodos.size(); p++) {
            PeriodoAcademico periodo = periodos.get(p);
            LocalDate[] rango = rangoDePeriodo(periodo.periodo(), p, periodos.size());
            List<NotaMateria> materias = new ArrayList<>();
            for (int i = 0; i < periodo.materias().size(); i++) {
                NotaMateria materia = periodo.materias().get(i);
                LocalDate fecha = materia.fecha() != null && !materia.fecha().isBlank()
                        ? LocalDate.parse(materia.fecha())
                        : rango[1].minusDays(4L * i + 2);
                if (fecha.isAfter(LocalDate.now())) {
                    fecha = LocalDate.now().minusDays(2L * i + 1);
                }
                materias.add(materia.conFecha(fecha.toString()));
            }
            salida.add(new PeriodoAcademico(periodo.periodo(), periodo.etiqueta(), periodo.promedio(),
                    periodo.faltas(), periodo.riesgo(), periodo.riesgoScore(), List.copyOf(materias),
                    rango[0].toString(), rango[1].toString()));
        }
        return List.copyOf(salida);
    }

    /** Rango de fechas del semestre; para los cortes genéricos usa los últimos seis meses. */
    private LocalDate[] rangoDePeriodo(String periodo, int indice, int total) {
        try {
            String[] partes = periodo.split("-");
            int anio = Integer.parseInt(partes[0].trim());
            int numero = Integer.parseInt(partes[1].trim());
            if (numero == 1) {
                return new LocalDate[]{LocalDate.of(anio, 2, 3), LocalDate.of(anio, 6, 27)};
            }
            return new LocalDate[]{LocalDate.of(anio, 8, 3), LocalDate.of(anio, 11, 27)};
        } catch (RuntimeException e) {
            LocalDate fin = LocalDate.now().minusMonths((long) Math.max(0, total - indice - 1) * 6);
            return new LocalDate[]{fin.minusMonths(5), fin};
        }
    }

    /** Perfil aproximado para cualquier otro estudiante, por si se consulta desde una ficha. */
    private List<PeriodoAcademico> periodosGenericos(Estudiante estudiante) {
        List<Integer> historial = estudiante.historialRiesgo();
        List<PeriodoAcademico> periodos = new ArrayList<>();
        for (int i = 0; i < historial.size(); i++) {
            int score = historial.get(i);
            double promedio = redondear(Math.max(1.0, Math.min(5.0, 5.0 - (score / 25.0))));
            String riesgo = score >= 70 ? "ALTO" : score >= 45 ? "MEDIO" : "BAJO";
            List<NotaMateria> materias = new ArrayList<>();
            for (NotaMateria materia : estudiante.materias()) {
                double nota = Math.max(1.0, Math.min(5.0, materia.nota() + (i - historial.size() + 1) * 0.08));
                materias.add(new NotaMateria(materia.nombre(), redondear(nota),
                        materia.faltas(), materia.faltasJustificadas(), materia.semestre(),
                        materia.grupo(), materia.homologada()));
            }
            periodos.add(new PeriodoAcademico("Corte " + (i + 1), "Corte " + (i + 1), promedio,
                    Math.max(0, estudiante.faltas() + (historial.size() - i - 1) * 2), riesgo, score,
                    List.copyOf(materias)));
        }
        return periodos.isEmpty()
                ? List.of(new PeriodoAcademico("Actual", "Corte actual", estudiante.promedio(),
                        estudiante.faltas(), estudiante.riesgoActual(), estudiante.riesgoScore(),
                        estudiante.materias()))
                : List.copyOf(periodos);
    }

    private String lectura(ResumenPersonal inicio, ResumenPersonal actual) {
        double diferencia = redondear(actual.promedio() - inicio.promedio());
        String tendencia = diferencia >= 0
                ? String.format(Locale.US, "subiste %.1f puntos", diferencia)
                : String.format(Locale.US, "bajaste %.1f puntos", Math.abs(diferencia));
        return String.format(Locale.US,
                "Empezaste en %s con promedio %.1f y riesgo %s. Hoy tu promedio es %.1f y tu riesgo es %s: "
                        + "%s y pasaste de %d a %d faltas. El acompañamiento y las tutorías están dando resultado.",
                inicio.periodo(), inicio.promedio(), inicio.riesgo().toLowerCase(Locale.ROOT),
                actual.promedio(), actual.riesgo().toLowerCase(Locale.ROOT), tendencia,
                inicio.faltas(), actual.faltas());
    }

    private static double redondear(double valor) {
        return Math.round(valor * 10.0) / 10.0;
    }
}
