package co.edu.permanencia.demo.students;

import co.edu.permanencia.demo.common.ApiException;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Carga y administra los estudiantes de prueba.
 *
 * <p>Si existe el archivo {@code data/estudiantes.csv} (generado por
 * {@code scripts/generar_estudiantes.py}) se cargan sus más de 14.000 registros de las 17 sedes.
 * Si no existe, se genera la muestra determinista de 600 estudiantes con la que nació la demo.</p>
 */
@Service
public class EstudianteService {

    public static final List<String> CARRERAS = List.of(
            "Enfermería",
            "Medicina",
            "Derecho",
            "Ingeniería de Software",
            "Ingeniería Industrial",
            "Odontología");

    public static final List<String> CATEGORIAS = List.of(
            "Crisis económica",
            "Depresión familiar",
            "Desconexión al futuro",
            "Estrés académico",
            "Desmotivación");

    /** Cada semestre se divide en dos grupos: A y B. */
    public static final List<String> GRUPOS = List.of("A", "B");

    /** Estudiantes objetivo por semestre del programa bandera de la demo. */
    private static final int META_POR_SEMESTRE = 66;

    /** Sedes reales de la UCC. */
    public static final List<String> SEDES = List.of(
            "Apartadó", "Arauca", "Barrancabermeja", "Bogotá", "Bucaramanga",
            "Cali", "Cartago", "El Espinal", "Ibagué", "Medellín",
            "Montería", "Neiva", "Pasto", "Pereira", "Popayán",
            "Santa Marta", "Villavicencio");

    /**
     * Id fijo del estudiante demo asociado a la cuenta estudiante@ucc.edu.co.
     *
     * <p>Se elige por encima del máximo del CSV para no chocar con los registros cargados.</p>
     */
    public static final int ESTUDIANTE_DEMO_ID = 16000;

    private static final Map<String, List<String>> MATERIAS = Map.of(
            "Enfermería", List.of("Fundamentos de Enfermería", "Anatomía", "Farmacología", "Cuidado del Adulto"),
            "Medicina", List.of("Anatomía", "Fisiología", "Bioquímica", "Semiología"),
            "Derecho", List.of("Derecho Civil", "Derecho Penal", "Constitución", "Derecho Laboral"),
            "Ingeniería de Software", List.of("Cálculo Diferencial", "Programación Orientada a Objetos",
                    "Bases de Datos", "Estructuras de Datos"),
            "Ingeniería de Sistemas", List.of("Cálculo Diferencial", "Programación Orientada a Objetos",
                    "Bases de Datos", "Estructuras de Datos"),
            "Ingeniería Industrial", List.of("Cálculo", "Física", "Investigación de Operaciones", "Logística"),
            "Odontología", List.of("Anatomía Dental", "Operatoria", "Periodoncia", "Radiología"));

    /**
     * Plan de estudios de Ingeniería de Software por semestre. Se usa para que las materias de
     * cada estudiante coincidan con su semestre y para permitir homologaciones puntuales.
     */
    private static final Map<Integer, List<String>> PLAN_SOFTWARE = Map.of(
            1, List.of("Cálculo Diferencial", "Introducción a la Ingeniería",
                    "Lógica de Programación", "Competencias Comunicativas"),
            2, List.of("Cálculo Integral", "Programación Orientada a Objetos",
                    "Bases de Datos I", "Inglés I"),
            3, List.of("Estructuras de Datos", "Bases de Datos II",
                    "Arquitectura de Computadores", "Inglés II"),
            4, List.of("Análisis de Algoritmos", "Ingeniería de Software I",
                    "Estadística", "Sistemas Operativos"),
            5, List.of("Ingeniería de Software II", "Redes de Computadores",
                    "Arquitectura de Software", "Gestión de Proyectos"),
            6, List.of("Pruebas de Software", "Seguridad Informática",
                    "Inteligencia Artificial", "Ética Profesional"),
            7, List.of("Desarrollo Web", "Computación en la Nube",
                    "Analítica de Datos", "Emprendimiento"),
            8, List.of("DevOps", "Microservicios", "Auditoría de Software",
                    "Legislación Informática"),
            9, List.of("Proyecto de Grado I", "Innovación", "Blockchain",
                    "Gestión del Talento"),
            10, List.of("Proyecto de Grado II", "Práctica Profesional",
                    "Portafolio", "Seminario de Investigación"));

    private static final Map<String, List<String>> RAZONES = Map.of(
            "Crisis económica", List.of(
                    "La familia no puede cubrir la matrícula ni los materiales del semestre; el estudiante trabaja de noche para aportar en casa y llega agotado a clase.",
                    "El ingreso principal del hogar se redujo y están atrasados con la matrícula; el estudiante ha dejado de comprar materiales y falta para trabajar.",
                    "Debe sostener económicamente a su familia y el costo del transporte le impide asistir con regularidad; está evaluando aplazar el semestre.",
                    "La beca que tenía no fue renovada y no cuenta con el dinero para la matrícula; ha manifestado que no sabe si podrá continuar."),
            "Depresión familiar", List.of(
                    "Atraviesa un duelo por la muerte de un familiar cercano y ha manifestado tristeza y ansiedad; su rendimiento bajó desde el último corte.",
                    "En su casa hay conflictos familiares graves y violencia intrafamiliar; le cuesta concentrarse y ha pedido apoyo en varias ocasiones.",
                    "Un familiar cercano está enfermo y el estudiante asume su cuidado; duerme poco y ha disminuido su asistencia.",
                    "Sus padres se separaron y la situación económica y emocional del hogar cambió; se aísla de sus compañeros y participa menos en clase."),
            "Desconexión al futuro", List.of(
                    "Siente que la carrera no era lo que esperaba y no le encuentra salida laboral; ha pensado en cambiarse de programa.",
                    "Ingresó por presión familiar y no por vocación; duda de su elección y ha dejado de involucrarse en las clases.",
                    "No ve relación entre lo que estudia y el trabajo que quiere tener; está evaluando retirarse para buscar otra opción.",
                    "Le preocupa el futuro laboral de su profesión y siente que no está aprendiendo lo necesario; su motivación ha caído."),
            "Estrés académico", List.of(
                    "Se siente sobrepasado por la carga de parciales y trabajos; no tiene hábitos de estudio y presenta ansiedad antes de los exámenes.",
                    "Perdió el control del semestre por acumulación de entregas; estudia de noche y ha empezado a faltar para poder cumplir.",
                    "Las materias con mayor dificultad lo están desbordando; evita pedir ayuda y se bloquea en las evaluaciones.",
                    "La exigencia académica le está generando insomnio y crisis de ansiedad; siente que no puede con todas las materias."),
            "Desmotivación", List.of(
                    "Ha perdido el interés por las clases, las considera poco prácticas y su asistencia se ha vuelto intermitente.",
                    "Siente apatía por el estudio y no encuentra sentido a las actividades del semestre; participa cada vez menos.",
                    "Las clases no le motivan y no tiene metas claras a corto plazo; su rendimiento se ha mantenido bajo.",
                    "Se le ve desanimado y sin iniciativa; no entrega varias actividades y expresa que la universidad no le está aportando."));

    private static final String[] NOMBRES = {
            "Juan Carlos", "María José", "Andrés Felipe", "Laura Sofía", "Daniela", "Santiago", "Valentina",
            "Sebastián", "Camila", "Alejandro", "Isabella", "Mateo", "Mariana", "Nicolás", "Gabriela",
            "Samuel", "Sara", "Emmanuel", "Luciana", "Tomás", "Antonia", "Julián", "Salomé", "Felipe",
            "Manuela", "Diego", "Paula", "Jorge", "Carolina", "Miguel Ángel", "Ana María", "Esteban",
            "Natalia", "David", "Juliana", "Simón", "María Fernanda", "Juan Pablo", "Catalina", "Jhon"
    };

    private static final String[] APELLIDOS = {
            "Pérez", "Gómez", "Rodríguez", "Díaz", "Martínez", "García", "López", "Hernández", "Ramírez",
            "Torres", "Vargas", "Castro", "Rojas", "Moreno", "Jiménez", "Mendoza", "Suárez", "Ortiz",
            "Rincón", "Cardona", "Zapata", "Restrepo", "Giraldo", "Muñoz", "Salazar", "Valencia", "Cárdenas",
            "Quintero", "Molina", "Arias", "Cortés", "Navarro", "Peña", "Sánchez", "Ríos", "Acosta",
            "Bermúdez", "Ospina", "Mejía", "Cruz"
    };

    private final List<Estudiante> estudiantes = new ArrayList<>();
    private final Map<Integer, Estudiante> indice = new HashMap<>();
    private volatile List<String> cacheProgramas;
    private volatile List<String> cacheSedes;
    private volatile List<String> cacheFacultades;
    /** Justificaciones radicadas por estudiante, semestre y materia (la demo vive en memoria). */
    private final Map<String, Integer> justificadasExtra = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private int siguienteIdSintetico = ID_SINTETICO_INICIO;
    private final Random random = new Random(20261106L);
    private final Map<String, String> facultadDePrograma = new LinkedHashMap<>();

    /** Caché de textos normalizados para buscar sin recorrer tildes en cada estudiante. */
    private final Map<String, String> cacheNormalizados = new ConcurrentHashMap<>();

    /** Población total objetivo de la demo. Se puede ajustar con la variable DEMO_POBLACION. */
    private static final int POBLACION_OBJETIVO = poblacionObjetivo();

    /** Ids sintéticos por encima del CSV y del estudiante demo para no chocar. */
    private static final int ID_SINTETICO_INICIO = 20_000;

    private static int poblacionObjetivo() {
        String valor = System.getenv("DEMO_POBLACION");
        if (valor != null && !valor.isBlank()) {
            try {
                return Math.max(1_000, Integer.parseInt(valor.trim()));
            } catch (NumberFormatException ignorado) {
                // Si el valor no es numérico se usa el objetivo nacional.
            }
        }
        return 1_109_845;
    }

    public EstudianteService() {
        if (!cargarCsv()) {
            sembrarDemo();
        }
        sembrarEstudianteDemo();
        completarCohortesSoftware();
        sembrarPoblacionAdicional();
        reindexar();
    }

    private void reindexar() {
        indice.clear();
        for (Estudiante e : estudiantes) {
            indice.put(e.id(), e);
        }
    }

    public List<Estudiante> listar(String search, String carrera, Integer semestre, String categoria,
                                   String riesgo, Integer limit) {
        return listar(search, carrera, null, semestre, categoria, riesgo, null, limit);
    }

    public List<Estudiante> listar(String search, String carrera, String sede, Integer semestre,
                                   String categoria, String riesgo, Integer limit) {
        return listar(search, carrera, sede, semestre, categoria, riesgo, null, limit);
    }

    public List<Estudiante> listar(String search, String carrera, String sede, Integer semestre,
                                   String categoria, String riesgo, String estado, Integer limit) {
        return listar(search, carrera, sede, null, semestre, null, categoria, riesgo, estado, limit);
    }

    /**
     * Filtro completo: además de programa y sede permite acotar por facultad y por el conjunto de
     * semestres permitidos (el profesor solo ve los semestres que dicta).
     */
    public List<Estudiante> listar(String search, String carrera, String sede, String facultad,
                                   Integer semestre, List<Integer> semestres, String categoria,
                                   String riesgo, String estado, Integer limit) {
        return listar(search, carrera, sede, facultad, semestre, semestres, categoria, riesgo,
                estado, null, limit);
    }

    public List<Estudiante> listar(String search, String carrera, String sede, String facultad,
                                   Integer semestre, List<Integer> semestres, String categoria,
                                   String riesgo, String estado, String grupo, Integer limit) {
        Comparator<Estudiante> orden = Comparator.comparingInt(Estudiante::riesgoScore).reversed()
                .thenComparing(Estudiante::apellido);
        Stream<Estudiante> stream = streamFiltrado(search, carrera, sede, facultad, semestre,
                semestres, categoria, riesgo, estado, grupo);
        if (limit != null && limit > 0) {
            // Selecciona solo los mejores N sin ordenar un millón de registros.
            PriorityQueue<Estudiante> cola = new PriorityQueue<>(limit, orden.reversed());
            stream.forEach(e -> {
                if (cola.size() < limit) {
                    cola.add(e);
                } else if (orden.compare(e, cola.peek()) < 0) {
                    cola.poll();
                    cola.add(e);
                }
            });
            List<Estudiante> salida = new ArrayList<>(cola);
            salida.sort(orden);
            return List.copyOf(salida);
        }
        return stream.sorted(orden).toList();
    }

    /** Filtro completo sin ordenar ni copiar la población; lo usan reportes y agregados. */
    public Stream<Estudiante> streamFiltrado(String search, String carrera, String sede,
                                             String facultad, Integer semestre,
                                             List<Integer> semestres, String categoria,
                                             String riesgo, String estado, String grupo) {
        String consulta = search == null ? "" : normalizar(search);
        return estudiantes.stream()
                .filter(e -> consulta.isBlank() || coincide(e, consulta))
                .filter(e -> vacio(carrera) || e.carrera().equalsIgnoreCase(carrera))
                .filter(e -> vacio(sede) || e.sede().equalsIgnoreCase(sede))
                .filter(e -> vacio(facultad) || e.facultad().equalsIgnoreCase(facultad))
                .filter(e -> semestre == null || e.semestre() == semestre)
                .filter(e -> semestres == null || semestres.isEmpty() || semestres.contains(e.semestre()))
                .filter(e -> vacio(categoria) || e.categoria().equalsIgnoreCase(categoria))
                .filter(e -> vacio(riesgo) || e.riesgoActual().equalsIgnoreCase(riesgo))
                .filter(e -> vacio(estado) || e.estado().equalsIgnoreCase(estado))
                .filter(e -> vacio(grupo) || e.grupo().equalsIgnoreCase(grupo));
    }

    public List<Estudiante> todos() {
        return List.copyOf(estudiantes);
    }

    public List<String> programas() {
        List<String> cache = cacheProgramas;
        if (cache != null) {
            return cache;
        }
        TreeSet<String> valores = new TreeSet<>();
        for (Estudiante e : estudiantes) {
            valores.add(e.carrera());
        }
        cacheProgramas = List.copyOf(valores);
        return cacheProgramas;
    }

    public List<String> programasDe(String sede) {
        if (vacio(sede)) {
            return programas();
        }
        TreeSet<String> valores = new TreeSet<>();
        for (Estudiante e : estudiantes) {
            if (e.sede().equalsIgnoreCase(sede)) {
                valores.add(e.carrera());
            }
        }
        return List.copyOf(valores);
    }

    public List<String> programasDeFacultad(String facultad) {
        if (vacio(facultad)) {
            return programas();
        }
        TreeSet<String> valores = new TreeSet<>();
        for (Estudiante e : estudiantes) {
            if (e.facultad().equalsIgnoreCase(facultad)) {
                valores.add(e.carrera());
            }
        }
        return List.copyOf(valores);
    }

    public List<String> sedes() {
        List<String> cache = cacheSedes;
        if (cache != null) {
            return cache;
        }
        TreeSet<String> valores = new TreeSet<>();
        for (Estudiante e : estudiantes) {
            valores.add(e.sede());
        }
        cacheSedes = List.copyOf(valores);
        return cacheSedes;
    }

    public List<String> facultades() {
        List<String> cache = cacheFacultades;
        if (cache != null) {
            return cache;
        }
        TreeSet<String> valores = new TreeSet<>();
        for (Estudiante e : estudiantes) {
            valores.add(e.facultad());
        }
        cacheFacultades = List.copyOf(valores);
        return cacheFacultades;
    }

    private void invalidarCatalogos() {
        cacheProgramas = null;
        cacheSedes = null;
        cacheFacultades = null;
    }

    public String sedeDePrograma(String programa) {
        return estudiantes.stream()
                .filter(e -> e.carrera().equalsIgnoreCase(programa))
                .map(Estudiante::sede)
                .findFirst()
                .orElse(null);
    }

    public String facultadDe(String programa) {
        return estudiantes.stream()
                .filter(e -> e.carrera().equalsIgnoreCase(programa))
                .map(Estudiante::facultad)
                .findFirst()
                .orElse(null);
    }

    public List<String> facultadesDe(String sede) {
        if (vacio(sede)) {
            return facultades();
        }
        TreeSet<String> valores = new TreeSet<>();
        for (Estudiante e : estudiantes) {
            if (e.sede().equalsIgnoreCase(sede)) {
                valores.add(e.facultad());
            }
        }
        return List.copyOf(valores);
    }

    public List<String> sedesDeFacultad(String facultad) {
        if (vacio(facultad)) {
            return sedes();
        }
        TreeSet<String> valores = new TreeSet<>();
        for (Estudiante e : estudiantes) {
            if (e.facultad().equalsIgnoreCase(facultad)) {
                valores.add(e.sede());
            }
        }
        return List.copyOf(valores);
    }

    public Estudiante porId(int id) {
        Estudiante encontrado = indice.get(id);
        if (encontrado != null) {
            return encontrado;
        }
        return estudiantes.stream().filter(e -> e.id() == id).findFirst()
                .orElseThrow(() -> ApiException.notFound("Estudiante no encontrado"));
    }

    /**
     * Registra una falta justificada para una materia y un semestre concretos.
     *
     * <p>El perfil del estudiante tiene varios períodos y no todos viven en la lista de materias
     * actual, por eso el conteo se guarda aparte y {@link #justificadasExtra(int, int, String)} lo
     * entrega cuando se arma el perfil. Así la justificación de un período anterior también se
     * refleja en la ficha.</p>
     */
    public synchronized Estudiante justificarFalta(int estudianteId, String materia, Integer semestre) {
        for (int i = 0; i < estudiantes.size(); i++) {
            Estudiante e = estudiantes.get(i);
            if (e.id() != estudianteId) {
                continue;
            }
            if (materia != null && semestre != null) {
                justificadasExtra.merge(clave(estudianteId, semestre, materia), 1, Integer::sum);
            }
            List<NotaMateria> materias = new ArrayList<>();
            for (NotaMateria m : e.materias()) {
                boolean coincide = materia != null && m.nombre().equalsIgnoreCase(materia)
                        && (semestre == null || m.semestre() == semestre);
                if (coincide && m.faltasJustificadas() < m.faltas()) {
                    materias.add(new NotaMateria(m.nombre(), m.nota(), m.faltas(),
                            m.faltasJustificadas() + 1, m.semestre(), m.grupo(), m.homologada(),
                            m.fecha()));
                } else {
                    materias.add(m);
                }
            }
            Estudiante actualizado = new Estudiante(e.id(), e.nombre(), e.apellido(), e.cedula(),
                    e.carrera(), e.facultad(), e.sede(), e.semestre(), e.grupo(), e.email(),
                    e.telefono(), e.categoria(), e.razon(), e.promedio(),
                    Math.max(0, e.faltas() - 1), e.riesgoActual(), e.riesgoScore(), e.estado(),
                    e.fechaIngreso(), List.copyOf(materias), e.historialRiesgo());
            estudiantes.set(i, actualizado);
            indice.put(actualizado.id(), actualizado);
            return actualizado;
        }
        throw ApiException.notFound("Estudiante no encontrado");
    }

    /** Justificaciones adicionales radicadas para una materia de un semestre. */
    public int justificadasExtra(int estudianteId, int semestre, String materia) {
        if (materia == null) {
            return 0;
        }
        return justificadasExtra.getOrDefault(clave(estudianteId, semestre, materia), 0);
    }

    private static String clave(int estudianteId, int semestre, String materia) {
        return estudianteId + "|" + semestre + "|" + materia.trim().toLowerCase(Locale.ROOT);
    }

    public synchronized Estudiante crear(EstudianteRequest request) {
        if (request.nombre() == null || request.nombre().isBlank()
                || request.apellido() == null || request.apellido().isBlank()) {
            throw ApiException.badRequest("Nombre y apellido son obligatorios");
        }
        if (request.cedula() == null || request.cedula().isBlank()) {
            throw ApiException.badRequest("La cédula es obligatoria");
        }
        if (request.carrera() == null || request.carrera().isBlank()) {
            throw ApiException.badRequest("El programa es obligatorio");
        }
        if (!CATEGORIAS.contains(request.categoria())) {
            throw ApiException.badRequest("La categoría no es válida");
        }

        int semestre = request.semestre() == null ? 1 : Math.max(1, Math.min(10, request.semestre()));
        String grupo = grupoValido(request.grupo());
        double promedio = redondear(clamp(request.promedio() == null ? 3.0 : request.promedio(), 0.0, 5.0));
        int faltas = request.faltas() == null ? 0 : Math.max(0, request.faltas());
        String estado = request.estado() == null ? "ACTIVO" : request.estado().toUpperCase(Locale.ROOT);
        if (!List.of("ACTIVO", "RETIRADO", "APLAZADO").contains(estado)) {
            estado = "ACTIVO";
        }

        int score = calcularScore(request.categoria(), promedio, faltas, semestre, estado);
        String riesgo = score >= 70 ? "ALTO" : score >= 45 ? "MEDIO" : "BAJO";
        String razon = request.razon() == null || request.razon().isBlank()
                ? "Sin razón registrada." : request.razon().trim();
        String email = request.email() == null || request.email().isBlank()
                ? normalizar(request.nombre()) + "." + normalizar(request.apellido()) + "@universidad.edu.co"
                : request.email().trim();
        String facultad = facultadDePrograma.getOrDefault(request.carrera(), "Facultad de Ingeniería");

        Estudiante estudiante = new Estudiante(
                nextId.getAndIncrement(),
                request.nombre().trim(),
                request.apellido().trim(),
                request.cedula().trim(),
                request.carrera(),
                facultad,
                "Bogotá",
                semestre,
                grupo,
                email,
                request.telefono() == null ? "" : request.telefono().trim(),
                request.categoria(),
                razon,
                promedio,
                faltas,
                riesgo,
                score,
                estado,
                LocalDate.now().minusYears(Math.max(0, (semestre - 1) / 2)).withDayOfMonth(15).toString(),
                materiasPara(request.carrera(), semestre, grupo, promedio, random),
                historialPara(score, riesgo, random));
        estudiantes.add(estudiante);
        indice.put(estudiante.id(), estudiante);
        invalidarCatalogos();
        return estudiante;
    }

    /** Carga el CSV masivo generado por scripts/generar_estudiantes.py. */
    private boolean cargarCsv() {
        try (InputStream entrada = getClass().getClassLoader()
                .getResourceAsStream("data/estudiantes.csv")) {
            if (entrada == null) {
                return false;
            }
            try (BufferedReader lector = new BufferedReader(
                    new InputStreamReader(entrada, StandardCharsets.UTF_8))) {
                String linea = lector.readLine(); // encabezado
                if (linea == null) {
                    return false;
                }
                while ((linea = lector.readLine()) != null) {
                    String[] campos = linea.split(",", -1);
                    if (campos.length < 12) {
                        continue;
                    }
                    try {
                        agregarDesdeCsv(campos);
                    } catch (RuntimeException ignorado) {
                        // Una fila defectuosa no debe tumbar la carga completa.
                    }
                }
            }
            nextId.set(estudiantes.size() + 1);
            return !estudiantes.isEmpty();
        } catch (Exception ex) {
            estudiantes.clear();
            return false;
        }
    }

    private void agregarDesdeCsv(String[] campos) {
        int id = Integer.parseInt(campos[0].trim());
        String completo = campos[1].trim();
        String cedula = campos[2].trim();
        String sede = campos[3].trim();
        String facultad = campos[4].trim();
        // El generador histórico usó "Ingeniería de Sistemas"; la demo actual trabaja con
        // "Ingeniería de Software", así que se unifica el nombre al cargar los datos.
        String programa = "Ingeniería de Sistemas".equalsIgnoreCase(campos[5].trim())
                ? "Ingeniería de Software" : campos[5].trim();
        int semestre = Integer.parseInt(campos[6].trim());
        String causa = campos[8].trim();
        String riesgo = campos[9].trim().toUpperCase(Locale.ROOT);
        double promedio = Double.parseDouble(campos[10].trim());
        int faltas = Integer.parseInt(campos[11].trim());

        String[] partes = completo.split("\\s+");
        String nombre;
        String apellido;
        if (partes.length >= 3) {
            nombre = String.join(" ", List.of(partes).subList(0, partes.length - 2));
            apellido = partes[partes.length - 2] + " " + partes[partes.length - 1];
        } else if (partes.length == 2) {
            nombre = partes[0];
            apellido = partes[1];
        } else {
            nombre = completo;
            apellido = "";
        }

        String categoria = categoriaDesdeCausa(causa);
        int score = puntaje(riesgo, promedio, faltas);
        String estado = estadoSegunRiesgo(riesgo, id);
        Random rnd = new Random(id);
        String email = normalizar(nombre).replace(" ", ".") + "." + normalizar(apellido.split(" ")[0])
                + id + "@ucc.edu.co";
        String telefono = String.format("3%09d", (id * 7919L) % 900_000_000L + 10_000_000L);
        int anioIngreso = 2026 - Math.max(0, (semestre - 1) / 2);
        int mesIngreso = semestre % 2 == 0 ? 1 : 7;
        String razon = causa + " " + String.format(Locale.US,
                "Promedio actual: %.1f; faltas acumuladas: %d.", promedio, faltas);

        facultadDePrograma.putIfAbsent(programa, facultad);
        String grupo = grupoAsignado(id, semestre);
        estudiantes.add(new Estudiante(
                id, nombre, apellido, cedula, programa, facultad, sede, semestre, grupo, email,
                telefono, categoria, razon, promedio, faltas, riesgo, score, estado,
                LocalDate.of(anioIngreso, mesIngreso, 15).toString(),
                materiasPara(programa, semestre, grupo, promedio, rnd),
                historialPara(score, riesgo, rnd)));
    }

    private static int puntaje(String riesgo, double promedio, int faltas) {
        int crudo = (int) Math.round(55 + (3.5 - promedio) * 18 + faltas * 2.2);
        return switch (riesgo) {
            case "ALTO" -> clamp(crudo, 72, 98);
            case "MEDIO" -> clamp(crudo, 46, 69);
            default -> clamp(crudo, 8, 44);
        };
    }

    private static String estadoSegunRiesgo(String riesgo, int id) {
        int modulo = id % 20;
        return switch (riesgo) {
            case "ALTO" -> modulo < 4 ? "RETIRADO" : modulo < 7 ? "APLAZADO" : "ACTIVO";
            case "MEDIO" -> modulo < 2 ? "RETIRADO" : modulo < 4 ? "APLAZADO" : "ACTIVO";
            default -> modulo < 1 ? "APLAZADO" : "ACTIVO";
        };
    }

    private static String categoriaDesdeCausa(String causa) {
        String t = normalizar(causa);
        if (t.contains("economic") || t.contains("matricula") || t.contains("trabaja")
                || t.contains("transporte") || t.contains("beca") || t.contains("prestamo")) {
            return "Crisis económica";
        }
        if (t.contains("familiar") || t.contains("duelo") || t.contains("emocional")
                || t.contains("ansiedad") || t.contains("depres") || t.contains("casa")) {
            return "Depresión familiar";
        }
        if (t.contains("vocacion") || t.contains("programa") || t.contains("futuro")
                || t.contains("desmotiv") || t.contains("carrera")) {
            return "Desconexión al futuro";
        }
        if (t.contains("matematic") || t.contains("academic") || t.contains("parcial")
                || t.contains("materia") || t.contains("estudio") || t.contains("asistencia")
                || t.contains("faltas") || t.contains("horario") || t.contains("distancia")) {
            return "Estrés académico";
        }
        return "Desmotivación";
    }

    /**
     * Estudiante demo con historial académico y de acompañamiento de más de un año.
     *
     * <p>Representa el caso que ve la cuenta estudiante@ucc.edu.co: ingresó en 2024-2 con riesgo
     * alto y hoy cursa quinto semestre con riesgo medio y notas en ascenso. El quinto semestre
     * coincide con uno de los que dicta el profesor demo, así el hilo de chat es coherente.</p>
     */
    private void sembrarEstudianteDemo() {
        boolean existe = estudiantes.stream().anyMatch(e -> e.id() == ESTUDIANTE_DEMO_ID);
        if (!existe) {
            List<NotaMateria> materias = List.of(
                    new NotaMateria("Ingeniería de Software II", 4.2, 1, 1, 5, "A", false, "2026-11-06"),
                    new NotaMateria("Redes de Computadores", 3.9, 2, 1, 5, "A", false, "2026-11-04"),
                    new NotaMateria("Arquitectura de Software", 4.1, 0, 0, 5, "A", false, "2026-10-30"),
                    new NotaMateria("Gestión de Proyectos", 3.8, 1, 0, 5, "A", false, "2026-10-28"),
                    new NotaMateria("Bases de Datos II", 4.0, 0, 0, 3, "B", true, "2025-11-21"));
            estudiantes.add(new Estudiante(
                    ESTUDIANTE_DEMO_ID,
                    "Mariana",
                    "Cárdenas Ortiz",
                    "1080016000",
                    "Ingeniería de Software",
                    "Facultad de Ingeniería",
                    "Pasto",
                    5,
                    "A",
                    "mariana.cardenas@ucc.edu.co",
                    "3175551600",
                    "Crisis económica",
                    "Ingresó en 2024-2 con dificultades para cubrir la matrícula y el transporte. "
                            + "Con apoyo de becas, tutorías y acompañamiento de bienestar pasó de un promedio "
                            + "de 2.6 y riesgo alto a un promedio de 3.9 y riesgo medio. Hoy trabaja medio "
                            + "tiempo, pero ha sostenido la asistencia y está al día con las entregas.",
                    3.9,
                    2,
                    "MEDIO",
                    47,
                    "ACTIVO",
                    "2024-07-22",
                    materias,
                    List.of(91, 86, 79, 72, 64, 55, 47)));
        }
        nextId.set(Math.max(nextId.get(), ESTUDIANTE_DEMO_ID + 1));
    }

    /**
     * Completa Ingeniería de Software · Pasto hasta 66 estudiantes por semestre (33 en A y 33 en
     * B). Así el profesor tiene una cohorte realista en 1, 5, 6 y 8, y el jefe de programa en los
     * diez semestres.
     */
    private void completarCohortesSoftware() {
        for (int semestre = 1; semestre <= 10; semestre++) {
            for (String grupo : GRUPOS) {
                int s = semestre;
                long actuales = estudiantes.stream()
                        .filter(e -> "Ingeniería de Software".equalsIgnoreCase(e.carrera()))
                        .filter(e -> "Pasto".equalsIgnoreCase(e.sede()))
                        .filter(e -> e.semestre() == s)
                        .filter(e -> grupo.equalsIgnoreCase(e.grupo()))
                        .count();
                int faltan = Math.max(0, (META_POR_SEMESTRE / 2) - (int) actuales);
                for (int i = 0; i < faltan; i++) {
                    nuevoEstudianteSintetico("Ingeniería de Software", "Facultad de Ingeniería",
                            "Pasto", semestre, grupo, "software-" + semestre + grupo + i);
                }
            }
        }
    }

    /** Rellena la población nacional hasta el objetivo de la demo. */
    private void sembrarPoblacionAdicional() {
        if (estudiantes.size() >= POBLACION_OBJETIVO) {
            return;
        }
        Map<String, List<String>> programasPorSede = new LinkedHashMap<>();
        for (Estudiante e : estudiantes) {
            programasPorSede.computeIfAbsent(e.sede(), clave -> new ArrayList<>()).add(e.carrera());
        }
        programasPorSede.replaceAll((sede, lista) -> lista.stream().distinct().sorted().toList());

        Random rnd = new Random(20261112L);
        int guardia = 0;
        while (estudiantes.size() < POBLACION_OBJETIVO && guardia < POBLACION_OBJETIVO * 2) {
            guardia++;
            String sede = SEDES.get(rnd.nextInt(SEDES.size()));
            List<String> programas = programasPorSede.getOrDefault(sede, List.of("Ingeniería de Software"));
            String carrera = programas.get(rnd.nextInt(programas.size()));
            // Pasto · Ingeniería de Software ya tiene sus cohortes cuidadas.
            if ("Pasto".equalsIgnoreCase(sede) && "Ingeniería de Software".equalsIgnoreCase(carrera)) {
                continue;
            }
            int semestre = 1 + rnd.nextInt(10);
            String grupo = GRUPOS.get(rnd.nextInt(GRUPOS.size()));
            String facultad = facultadDePrograma.getOrDefault(carrera, facultadPara(carrera));
            nuevoEstudianteSintetico(carrera, facultad, sede, semestre, grupo,
                    "nacional-" + sede + "-" + carrera + "-" + guardia);
        }
    }

    private void nuevoEstudianteSintetico(String carrera, String facultad, String sede,
                                          int semestre, String grupo, String semilla) {
        int id = siguienteIdSintetico++;
        Random rnd = new Random((semilla + id).hashCode());
        String nombre = NOMBRES[rnd.nextInt(NOMBRES.length)];
        String apellido1 = APELLIDOS[rnd.nextInt(APELLIDOS.length)];
        String apellido2 = APELLIDOS[rnd.nextInt(APELLIDOS.length)];
        while (apellido2.equals(apellido1)) {
            apellido2 = APELLIDOS[rnd.nextInt(APELLIDOS.length)];
        }
        String apellido = apellido1 + " " + apellido2;
        String cedula = "10" + String.format("%08d", id);
        String email = normalizar(nombre).replace(" ", ".") + "." + normalizar(apellido1)
                + id + "@ucc.edu.co";
        String telefono = String.format("3%09d", (id * 7919L) % 900_000_000L + 10_000_000L);

        double dado = rnd.nextDouble();
        String nivel = dado < 0.06 ? "ALTO" : dado < 0.79 ? "MEDIO" : "BAJO";
        double promedio = switch (nivel) {
            case "ALTO" -> redondear(1.9 + rnd.nextDouble() * 1.1);
            case "MEDIO" -> redondear(2.9 + rnd.nextDouble() * 0.9);
            default -> redondear(3.7 + rnd.nextDouble() * 1.0);
        };
        int faltas = switch (nivel) {
            case "ALTO" -> 4 + rnd.nextInt(7);
            case "MEDIO" -> 1 + rnd.nextInt(5);
            default -> rnd.nextInt(3);
        };
        int score = switch (nivel) {
            case "ALTO" -> 72 + rnd.nextInt(24);
            case "MEDIO" -> 46 + rnd.nextInt(22);
            default -> 10 + rnd.nextInt(32);
        };
        score = clamp(score, 5, 98);
        double estadoDado = rnd.nextDouble();
        String estado = "ACTIVO";
        if ("ALTO".equals(nivel)) {
            estado = estadoDado < 0.18 ? "RETIRADO" : estadoDado < 0.28 ? "APLAZADO" : "ACTIVO";
        } else if ("MEDIO".equals(nivel)) {
            estado = estadoDado < 0.06 ? "RETIRADO" : estadoDado < 0.10 ? "APLAZADO" : "ACTIVO";
        } else if (estadoDado < 0.02) {
            estado = "APLAZADO";
        }

        String categoria = CATEGORIAS.get(rnd.nextInt(CATEGORIAS.size()));
        String razon = RAZONES.get(categoria).get(rnd.nextInt(RAZONES.get(categoria).size()))
                + " " + String.format(Locale.US,
                "Promedio actual: %.1f; faltas acumuladas: %d.", promedio, faltas);
        int anioIngreso = 2026 - Math.max(0, (semestre - 1) / 2);
        int mesIngreso = semestre % 2 == 0 ? 1 : 7;

        estudiantes.add(new Estudiante(
                id, nombre, apellido, cedula, carrera, facultad, sede, semestre, grupo, email,
                telefono, categoria, razon, promedio, faltas, nivel, score, estado,
                LocalDate.of(anioIngreso, mesIngreso, 15).toString(),
                materiasPara(carrera, semestre, grupo, promedio, rnd),
                historialPara(score, nivel, rnd)));
    }

    private void sembrarDemo() {
        List<String> niveles = new ArrayList<>();
        for (int i = 0; i < 228; i++) {
            niveles.add("MEDIO");
        }
        for (int i = 0; i < 180; i++) {
            niveles.add("BAJO");
        }
        Collections.shuffle(niveles, random);

        List<String> categorias = new ArrayList<>();
        for (int i = 0; i < 180; i++) {
            categorias.add("Crisis económica");
        }
        for (int i = 0; i < 108; i++) {
            categorias.add("Depresión familiar");
        }
        for (int i = 0; i < 120; i++) {
            categorias.add("Desconexión al futuro");
        }
        for (int i = 0; i < 120; i++) {
            categorias.add("Estrés académico");
        }
        for (int i = 0; i < 72; i++) {
            categorias.add("Desmotivación");
        }
        Collections.shuffle(categorias, random);

        int[] porSemestre = {90, 80, 70, 60, 60, 55, 50, 45, 45, 45};
        List<Integer> semestres = new ArrayList<>();
        for (int semestre = 1; semestre <= 10; semestre++) {
            for (int i = 0; i < porSemestre[semestre - 1]; i++) {
                semestres.add(semestre);
            }
        }
        Collections.shuffle(semestres, random);

        for (int i = 0; i < 600; i++) {
            String nivel = niveles.get(i);
            String categoria = categorias.get(i);
            String carrera = CARRERAS.get(i % CARRERAS.size());
            int semestre = semestres.get(i);

            double promedio = switch (nivel) {
                case "ALTO" -> 2.1 + random.nextDouble();
                case "MEDIO" -> 2.9 + random.nextDouble() * 0.9;
                default -> 3.6 + random.nextDouble() * 1.1;
            };
            promedio = redondear(promedio);
            int faltas = switch (nivel) {
                case "ALTO" -> 4 + random.nextInt(6);
                case "MEDIO" -> 1 + random.nextInt(5);
                default -> random.nextInt(3);
            };
            int score = switch (nivel) {
                case "ALTO" -> 72 + random.nextInt(24);
                case "MEDIO" -> 46 + random.nextInt(22);
                default -> 12 + random.nextInt(30);
            };
            score = clamp(score, 5, 98);

            double dado = random.nextDouble();
            String estado = "ACTIVO";
            if ("ALTO".equals(nivel)) {
                if (dado < 0.20) {
                    estado = "RETIRADO";
                } else if (dado < 0.30) {
                    estado = "APLAZADO";
                }
            } else if ("MEDIO".equals(nivel)) {
                if (dado < 0.08) {
                    estado = "RETIRADO";
                } else if (dado < 0.12) {
                    estado = "APLAZADO";
                }
            } else if (dado < 0.02) {
                estado = "APLAZADO";
            }

            String nombre = NOMBRES[random.nextInt(NOMBRES.length)];
            String apellido1 = APELLIDOS[random.nextInt(APELLIDOS.length)];
            String apellido2 = APELLIDOS[random.nextInt(APELLIDOS.length)];
            while (apellido2.equals(apellido1)) {
                apellido2 = APELLIDOS[random.nextInt(APELLIDOS.length)];
            }
            String apellido = apellido1 + " " + apellido2;
            String cedula = String.valueOf(100001 + i);
            String email = normalizar(nombre) + "." + normalizar(apellido1) + (i + 1) + "@universidad.edu.co";
            String telefono = String.format("3%09d", 10_000_000 + random.nextInt(90_000_000));
            String razonBase = RAZONES.get(categoria).get(random.nextInt(RAZONES.get(categoria).size()));
            String razon = razonBase + " " + String.format(Locale.US,
                    "Promedio actual: %.1f; faltas acumuladas: %d.", promedio, faltas);
            int anioIngreso = 2026 - Math.max(0, (semestre - 1) / 2);
            int mesIngreso = semestre % 2 == 0 ? 1 : 7;

            String grupo = grupoAsignado(i + 1, semestre);
            estudiantes.add(new Estudiante(
                    nextId.getAndIncrement(),
                    nombre,
                    apellido,
                    cedula,
                    carrera,
                    facultadPara(carrera),
                    "Bogotá",
                    semestre,
                    grupo,
                    email,
                    telefono,
                    categoria,
                    razon,
                    promedio,
                    faltas,
                    nivel,
                    score,
                    estado,
                    LocalDate.of(anioIngreso, mesIngreso, 15).toString(),
                    materiasPara(carrera, semestre, grupo, promedio, random),
                    historialPara(score, nivel, random)
            ));
        }
    }

    private static String facultadPara(String carrera) {
        return switch (carrera) {
            case "Enfermería", "Medicina", "Odontología" -> "Facultad de Salud";
            case "Derecho" -> "Facultad de Derecho";
            default -> "Facultad de Ingeniería";
        };
    }

    private static int calcularScore(String categoria, double promedio, int faltas, int semestre, String estado) {
        int base = switch (categoria) {
            case "Crisis económica" -> 66;
            case "Depresión familiar" -> 62;
            case "Desconexión al futuro" -> 55;
            case "Estrés académico" -> 58;
            default -> 50;
        };
        int score = base + (int) Math.round((3.5 - promedio) * 20) + faltas * 4 + (semestre <= 2 ? 4 : 0);
        if ("RETIRADO".equals(estado)) {
            score += 15;
        } else if ("APLAZADO".equals(estado)) {
            score += 7;
        }
        return clamp(score, 5, 98);
    }

    private List<NotaMateria> materiasPara(String carrera, int semestre, String grupo,
                                           double promedio, Random rnd) {
        List<String> base = materiasDeSemestre(carrera, semestre);
        List<NotaMateria> resultado = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String nombre = base.get(i % base.size());
            double nota = clamp(promedio + (rnd.nextDouble() * 1.4 - 0.7), 1.0, 5.0);
            int faltas = rnd.nextInt(4);
            int justificadas = faltas == 0 ? 0 : rnd.nextInt(faltas + 1);
            int semestreMateria = semestre;
            String grupoMateria = grupo;
            boolean homologada = false;
            // Algunas materias vienen homologadas de otro semestre o del otro grupo.
            if (i == 3 && semestre > 1 && rnd.nextDouble() < 0.18) {
                semestreMateria = semestre - 1;
                grupoMateria = "A".equals(grupo) ? "B" : "A";
                List<String> otras = materiasDeSemestre(carrera, semestreMateria);
                nombre = otras.get(rnd.nextInt(otras.size()));
                homologada = true;
            }
            resultado.add(new NotaMateria(nombre, redondear(nota), faltas, justificadas,
                    semestreMateria, grupoMateria, homologada,
                    LocalDate.now().minusMonths((10L - semestreMateria) * 6)
                            .minusDays(3L * i).toString()));
        }
        return List.copyOf(resultado);
    }

    private static List<String> materiasDeSemestre(String carrera, int semestre) {
        if ("Ingeniería de Software".equalsIgnoreCase(carrera)
                || "Ingeniería de Sistemas".equalsIgnoreCase(carrera)) {
            List<String> plan = PLAN_SOFTWARE.get(semestre);
            if (plan != null) {
                return plan;
            }
        }
        return MATERIAS.getOrDefault(carrera,
                List.of("Materia 1", "Materia 2", "Materia 3", "Materia 4"));
    }

    private static String grupoValido(String grupo) {
        if (grupo == null || grupo.isBlank()) {
            return "A";
        }
        String limpio = grupo.trim().toUpperCase(Locale.ROOT);
        return "B".equals(limpio) ? "B" : "A";
    }

    private static String grupoAsignado(int id, int semestre) {
        // Alterna A/B para que cada semestre tenga dos grupos equilibrados.
        return (Math.abs(id) + semestre) % 2 == 0 ? "A" : "B";
    }

    private List<Integer> historialPara(int score, String nivel, Random rnd) {
        List<Integer> historial = new ArrayList<>();
        if ("ALTO".equals(nivel)) {
            int inicio = Math.max(8, score - 25 - rnd.nextInt(10));
            for (int i = 0; i < 5; i++) {
                historial.add(clamp(inicio + i * 5 + rnd.nextInt(4) - 1, 5, 98));
            }
        } else if ("MEDIO".equals(nivel)) {
            for (int i = 0; i < 5; i++) {
                historial.add(clamp(score + rnd.nextInt(13) - 6, 5, 98));
            }
        } else {
            for (int i = 0; i < 5; i++) {
                historial.add(clamp(score + rnd.nextInt(9) - 4, 5, 98));
            }
        }
        historial.add(score);
        return List.copyOf(historial);
    }

    private static int clamp(double value, int min, int max) {
        return (int) Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double redondear(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static boolean vacio(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Búsqueda por texto sin normalizar la población completa en cada tecla: los valores de
     * nombre, apellido, programa, sede y grupo se repiten, así que se normalizan una sola vez.
     */
    private boolean coincide(Estudiante e, String consulta) {
        if (e.cedula().contains(consulta)) {
            return true;
        }
        if (normalizada(e.nombre()).contains(consulta) || normalizada(e.apellido()).contains(consulta)
                || normalizada(e.carrera()).contains(consulta)
                || normalizada(e.categoria()).contains(consulta)
                || normalizada(e.sede()).contains(consulta)
                || normalizada(e.facultad()).contains(consulta)
                || normalizada(e.grupo()).contains(consulta)) {
            return true;
        }
        // Permite buscar el nombre completo ("mariana cardenas").
        return consulta.indexOf(' ') >= 0
                && normalizada(e.nombre() + " " + e.apellido()).contains(consulta);
    }

    private String normalizada(String valor) {
        if (valor == null) {
            return "";
        }
        String cacheado = cacheNormalizados.get(valor);
        if (cacheado != null) {
            return cacheado;
        }
        String normal = normalizar(valor);
        if (cacheNormalizados.size() < 20_000) {
            cacheNormalizados.putIfAbsent(valor, normal);
        }
        return normal;
    }

    private static String normalizar(String value) {
        if (value == null) {
            return "";
        }
        String sinTildes = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return sinTildes.toLowerCase(Locale.ROOT).trim();
    }
}
