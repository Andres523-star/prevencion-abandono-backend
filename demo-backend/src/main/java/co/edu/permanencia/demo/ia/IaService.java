package co.edu.permanencia.demo.ia;

import co.edu.permanencia.demo.acompanamiento.AccionAcompanamiento;
import co.edu.permanencia.demo.acompanamiento.AcompanamientoService;
import co.edu.permanencia.demo.auth.Usuario;
import co.edu.permanencia.demo.common.Alcance;
import co.edu.permanencia.demo.perfil.PerfilEstudiante;
import co.edu.permanencia.demo.perfil.PerfilService;
import co.edu.permanencia.demo.perfil.PeriodoAcademico;
import co.edu.permanencia.demo.reports.Reporte;
import co.edu.permanencia.demo.reports.ReporteService;
import co.edu.permanencia.demo.students.Estudiante;
import co.edu.permanencia.demo.students.EstudianteService;
import co.edu.permanencia.demo.students.NotaMateria;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asistente de IA de la demo.
 *
 * <p>Por ahora es un motor de reglas: no llama a un modelo externo, pero responde con datos reales
 * del sistema y aplica la misma jerarquía que el resto de la plataforma. Cuando se conecte el
 * proveedor definitivo basta con reemplazar {@link #responder(Usuario, String)} por la llamada al
 * modelo, manteniendo el filtro de alcance previo.</p>
 */
@Service
public class IaService {

    private static final List<String> SUGERENCIAS_BASE = List.of(
            "¿Cómo está el riesgo en mi alcance?",
            "¿Qué estudiantes tienen más faltas?",
            "Compara los grupos A y B",
            "¿Qué materias están por debajo de 3.0?");

    /** Preguntas pensadas para el acompañamiento personal del estudiante. */
    private static final List<String> SUGERENCIAS_ESTUDIANTE = List.of(
            "¿Cómo va mi semestre?",
            "¿Cuál es mi promedio actual?",
            "¿Cómo voy en cada materia?",
            "¿Cómo voy en Redes de Computadores?",
            "¿Cuándo es mi próxima cita?",
            "¿Qué materias debo reforzar?",
            "¿Cuántas faltas tengo?",
            "¿Cómo puedo justificar una falta?",
            "¿Cómo ha cambiado mi riesgo?",
            "Me siento desmotivado");

    /** Preguntas rápidas para el profesor acompañante. */
    private static final List<String> SUGERENCIAS_PROFESOR = List.of(
            "¿Qué estudiantes debo citar esta semana?",
            "¿Cómo están mis grupos A y B?",
            "¿Quiénes tienen más faltas en mis semestres?",
            "¿Qué materias están en alerta?",
            "¿Cómo está el riesgo en mi programa?",
            "Compara mis grupos con el promedio del programa",
            "¿Qué estudiantes tienen riesgo alto?",
            "¿Cómo van mis cohortes de primero y quinto?");

    private final EstudianteService estudiantes;
    private final ReporteService reportes;
    private final PerfilService perfiles;
    private final AcompanamientoService acompanamiento;

    /** Historial confidencial por correo: cada usuario ve únicamente su propia conversación. */
    private final Map<String, List<MensajeIA>> historial = new ConcurrentHashMap<>();
    private final AtomicInteger secuencia = new AtomicInteger();

    public IaService(EstudianteService estudiantes, ReporteService reportes, PerfilService perfiles,
                     AcompanamientoService acompanamiento) {
        this.estudiantes = estudiantes;
        this.reportes = reportes;
        this.perfiles = perfiles;
        this.acompanamiento = acompanamiento;
    }

    public RespuestaIA responder(Usuario usuario, String mensaje) {
        if (usuario == null) {
            return new RespuestaIA("Inicia sesión para usar el asistente.", List.of(), "sin sesión", false);
        }
        String texto = mensaje == null ? "" : mensaje.trim();
        RespuestaIA respuesta = "ESTUDIANTE".equals(usuario.rol())
                ? responderEstudiante(usuario, texto)
                : responderInstitucional(usuario, texto);
        guardar(usuario, texto, respuesta.respuesta());
        return respuesta;
    }

    /** Historial de la cuenta autenticada, del turno más antiguo al más reciente. */
    public List<MensajeIA> historial(Usuario usuario) {
        if (usuario == null) {
            return List.of();
        }
        return List.copyOf(historial.getOrDefault(clave(usuario), List.of()));
    }

    /** Borra el historial de la cuenta y devuelve cuántos turnos se eliminaron. */
    public int limpiarHistorial(Usuario usuario) {
        if (usuario == null) {
            return 0;
        }
        List<MensajeIA> previos = historial.remove(clave(usuario));
        return previos == null ? 0 : previos.size();
    }

    private void guardar(Usuario usuario, String pregunta, String respuesta) {
        List<MensajeIA> hilo = historial.computeIfAbsent(clave(usuario),
                correo -> new CopyOnWriteArrayList<>());
        if (!pregunta.isBlank()) {
            hilo.add(new MensajeIA(secuencia.incrementAndGet(), "YO", pregunta,
                    OffsetDateTime.now().toString()));
        }
        if (respuesta != null && !respuesta.isBlank()) {
            hilo.add(new MensajeIA(secuencia.incrementAndGet(), "IA", respuesta,
                    OffsetDateTime.now().toString()));
        }
    }

    private static String clave(Usuario usuario) {
        return usuario.email() == null ? "usuario-" + usuario.id()
                : usuario.email().toLowerCase(Locale.ROOT);
    }

    private RespuestaIA responderInstitucional(Usuario usuario, String texto) {
        if (texto.isBlank()) {
            return new RespuestaIA("Cuéntame qué quieres revisar: riesgo, faltas, grupos, materias o "
                    + "comparaciones dentro de tu alcance.", sugerencias(usuario), alcance(usuario), true);
        }

        String restriccion = validarAlcance(usuario, texto);
        if (restriccion != null) {
            return new RespuestaIA(restriccion, sugerencias(usuario), alcance(usuario), false);
        }

        String carrera = Alcance.carrera(usuario, null);
        String sede = Alcance.sede(usuario, null);
        String facultad = Alcance.facultad(usuario, null);
        Reporte r = reportes.generar(carrera, sede, facultad, null, Alcance.semestres(usuario),
                null, null, null);
        String t = texto.toLowerCase(Locale.ROOT);

        if (contiene(t, "citar", "prioridad", "prioritarios", "esta semana", "acompañar primero",
                "intervenir")) {
            return new RespuestaIA(prioritarios(usuario), sugerencias(usuario), alcance(usuario), true);
        }
        if (t.contains("falta") || t.contains("asistencia") || t.contains("inasistencia")) {
            return new RespuestaIA(faltas(r, usuario), sugerencias(usuario), alcance(usuario), true);
        }
        if (t.contains("grupo") || t.contains(" a y b") || t.contains("4a") || t.contains("4b")) {
            return new RespuestaIA(grupos(r), sugerencias(usuario), alcance(usuario), true);
        }
        if (t.contains("materia") || t.contains("nota") || t.contains("promedio")) {
            return new RespuestaIA(materias(usuario, carrera, sede, facultad, r),
                    sugerencias(usuario), alcance(usuario), true);
        }
        if (t.contains("sede") || t.contains("facultad")) {
            return new RespuestaIA(sedes(r, usuario), sugerencias(usuario), alcance(usuario), true);
        }
        if (t.contains("semestre") || t.contains("periodo")) {
            return new RespuestaIA(semestres(r), sugerencias(usuario), alcance(usuario), true);
        }
        if (t.contains("riesgo") || t.contains("deserción") || t.contains("desercion")
                || t.contains("alerta")) {
            return new RespuestaIA(riesgo(r, usuario), sugerencias(usuario), alcance(usuario), true);
        }
        return new RespuestaIA(resumen(r, usuario), sugerencias(usuario), alcance(usuario), true);
    }

    /**
     * Respuesta personal para el rol Estudiante.
     *
     * <p>Solo usa los datos del propio estudiante: no consulta reportes institucionales ni otras
     * cuentas. El tono es cercano y recuerda que la conversación es confidencial.</p>
     */
    private RespuestaIA responderEstudiante(Usuario usuario, String texto) {
        String alcance = "tu proceso · conversación confidencial";
        if (usuario.estudianteId() == null) {
            return new RespuestaIA("Tu cuenta no tiene un estudiante asociado. Escríbenos a "
                    + "bienestar.pasto@ucc.edu.co para revisarlo.", SUGERENCIAS_ESTUDIANTE, alcance, false);
        }
        PerfilEstudiante p = perfiles.perfil(usuario.estudianteId());
        if (texto.isBlank()) {
            return new RespuestaIA(saludoEstudiante(p), SUGERENCIAS_ESTUDIANTE, alcance, true);
        }
        String t = texto.toLowerCase(Locale.ROOT);

        if (contiene(t, "siento", "triste", "ansios", "desmotivad", "estres", "estrés", "no puedo",
                "solo", "sola", "miedo", "confundid", "ayuda", "rendir", "cansad")) {
            return new RespuestaIA(apoyoEmocional(p), SUGERENCIAS_ESTUDIANTE, alcance, true);
        }
        if (contiene(t, "reuni", "reunión", "reunion", "cita", "próxima", "proxima", "agenda",
                "encuentro", "cuándo", "cuando")) {
            return new RespuestaIA(proximaCitaEstudiante(p), SUGERENCIAS_ESTUDIANTE, alcance, true);
        }
        String detalleMateria = materiaEspecifica(p, t);
        if (detalleMateria != null) {
            return new RespuestaIA(detalleMateria, SUGERENCIAS_ESTUDIANTE, alcance, true);
        }
        if (t.contains("falta") || t.contains("asistencia") || t.contains("inasistencia")
                || t.contains("justific")) {
            return new RespuestaIA(faltasEstudiante(p), SUGERENCIAS_ESTUDIANTE, alcance, true);
        }
        if (t.contains("materia") || t.contains("nota") || t.contains("promedio")
                || t.contains("reforzar")) {
            return new RespuestaIA(materiasEstudiante(p), SUGERENCIAS_ESTUDIANTE, alcance, true);
        }
        if (t.contains("riesgo") || t.contains("alerta") || t.contains("deserción")
                || t.contains("desercion") || t.contains("retir")) {
            return new RespuestaIA(riesgoEstudiante(p), SUGERENCIAS_ESTUDIANTE, alcance, true);
        }
        if (t.contains("semestre") || t.contains("periodo") || t.contains("historial")
                || t.contains("avance")) {
            return new RespuestaIA(historialEstudiante(p), SUGERENCIAS_ESTUDIANTE, alcance, true);
        }
        return new RespuestaIA(resumenEstudiante(p), SUGERENCIAS_ESTUDIANTE, alcance, true);
    }

    private String saludoEstudiante(PerfilEstudiante p) {
        return "Hola, " + p.nombre() + ". Este es un espacio privado entre tú y yo: tu historial de "
                + "conversación es confidencial y no lo ven tus profesores ni otros estudiantes. "
                + "Puedo acompañarte con tu semestre, tus materias, tus faltas o con lo que sientas. "
                + "¿Cómo estás hoy?";
    }

    private String apoyoEmocional(PerfilEstudiante p) {
        return "Gracias por contármelo, " + p.nombre() + ". Lo que sientes importa y no tienes que "
                + "cargarlo solo/a. Esta conversación es confidencial: puedo escucharte sin que otros "
                + "roles vean lo que escribes.\n\n"
                + "Una idea: elige una sola cosa pequeña para hoy (asistir a una clase, escribirle a "
                + "tu profesor acompañante o pedir una cita en Bienestar Universitario). Si la "
                + "desmotivación sigue varios días, buscar apoyo no es rendirse: es cuidarte.\n\n"
                + "¿Quieres que revisemos juntos cómo va tu semestre o qué materias te están pesando más?";
    }

    private String faltasEstudiante(PerfilEstudiante p) {
        int total = 0;
        int justificadas = 0;
        List<String> pendientes = new ArrayList<>();
        for (PeriodoAcademico periodo : p.periodos()) {
            for (NotaMateria m : periodo.materias()) {
                total += m.faltas();
                justificadas += m.faltasJustificadas();
                if (m.faltasPendientes() > 0) {
                    pendientes.add(m.nombre() + " (" + m.faltasPendientes() + ")");
                }
            }
        }
        int porJustificar = Math.max(0, total - justificadas);
        StringBuilder sb = new StringBuilder();
        sb.append("En tu proceso registras ").append(total).append(" falta(s): ")
                .append(justificadas).append(" ya están justificadas y ")
                .append(porJustificar).append(" siguen pendientes.");
        if (!pendientes.isEmpty()) {
            sb.append("\n\nPendientes: ").append(String.join(", ", pendientes)).append(".");
        }
        sb.append("\n\nPara justificar una falta entra a Mis estadísticas, abre el período y toca la "
                + "materia. Adjunta una foto de evidencia y envía el radicado a "
                + "bienestar.pasto@ucc.edu.co. Recuerda: sin foto no se puede completar el envío.");
        return sb.toString();
    }

    private String materiasEstudiante(PerfilEstudiante p) {
        PeriodoAcademico actual = p.periodos().getLast();
        List<String> detalle = new ArrayList<>();
        List<String> refuerzo = new ArrayList<>();
        for (NotaMateria m : actual.materias()) {
            detalle.add(m.nombre() + " " + String.format(Locale.US, "%.1f", m.nota()));
            if (m.nota() < 3.5) {
                refuerzo.add(m.nombre());
            }
        }
        StringBuilder sb = new StringBuilder("En ").append(actual.periodo()).append(" (" )
                .append(actual.etiqueta()).append(") llevas un promedio de ")
                .append(String.format(Locale.US, "%.1f", actual.promedio())).append(".\n\n")
                .append("Materias: ").append(String.join(", ", detalle)).append(".");
        if (refuerzo.isEmpty()) {
            sb.append("\n\nVas bien: no veo materias críticas este período. Mantén el ritmo y sigue "
                    + "entregando a tiempo.");
        } else {
            sb.append("\n\nTe sugiero reforzar ").append(String.join(", ", refuerzo))
                    .append(". Puedes pedir tutoría o hablar con tu profesor acompañante antes de "
                            + "la próxima evaluación.");
        }
        return sb.toString();
    }

    /** Responde el detalle de una materia concreta si el estudiante la nombra. */
    private String materiaEspecifica(PerfilEstudiante p, String texto) {
        NotaMateria encontrada = null;
        PeriodoAcademico periodoEncontrado = null;
        for (PeriodoAcademico periodo : p.periodos()) {
            for (NotaMateria materia : periodo.materias()) {
                String nombre = materia.nombre().toLowerCase(Locale.ROOT);
                if (texto.contains(nombre) || coincidePalabra(texto, nombre)) {
                    encontrada = materia;
                    periodoEncontrado = periodo;
                }
            }
        }
        if (encontrada == null || periodoEncontrado == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder("Tu historial en ").append(encontrada.nombre()).append(":\n");
        for (PeriodoAcademico periodo : p.periodos()) {
            for (NotaMateria materia : periodo.materias()) {
                if (materia.nombre().equalsIgnoreCase(encontrada.nombre())) {
                    sb.append("• ").append(periodo.periodo()).append(": nota ")
                            .append(String.format(Locale.US, "%.1f", materia.nota()))
                            .append(", ").append(materia.faltas()).append(" falta(s)")
                            .append(materia.faltasJustificadas() > 0
                                    ? " (" + materia.faltasJustificadas() + " justificada(s))" : "")
                            .append(".\n");
                }
            }
        }
        sb.append("\nEn el corte más reciente (").append(periodoEncontrado.periodo())
                .append(") tienes ").append(String.format(Locale.US, "%.1f", encontrada.nota()))
                .append(". ");
        sb.append(encontrada.nota() >= 3.5
                ? "Vas bien en esta materia; mantén el ritmo y entrega a tiempo."
                : "Te sugiero reforzar con tutoría y hablar con tu profesor antes de la próxima evaluación.");
        return sb.toString();
    }

    private static boolean coincidePalabra(String texto, String nombre) {
        for (String palabra : nombre.split("[^a-záéíóúñ]+")) {
            if (palabra.length() >= 5 && texto.contains(palabra)) {
                return true;
            }
        }
        return false;
    }

    /** Próxima cita o acompañamiento pendiente del estudiante. */
    private String proximaCitaEstudiante(PerfilEstudiante p) {
        List<AccionAcompanamiento> acciones = acompanamiento.de(p.estudianteId());
        Optional<AccionAcompanamiento> proxima = acciones.stream()
                .filter(accion -> "PENDIENTE".equalsIgnoreCase(accion.estado()))
                .filter(accion -> {
                    try {
                        return !LocalDate.parse(accion.fecha()).isBefore(LocalDate.now());
                    } catch (RuntimeException ignorado) {
                        return false;
                    }
                })
                .min(Comparator.comparing(AccionAcompanamiento::fecha));
        if (proxima.isEmpty()) {
            return "No tienes citas pendientes registradas. Si necesitas hablar con alguien, puedo "
                    + "ayudarte a pedir una cita con Bienestar Universitario o con tu profesor.";
        }
        AccionAcompanamiento cita = proxima.get();
        String fecha = LocalDate.parse(cita.fecha())
                .format(DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy",
                        new Locale("es", "CO")));
        return "Tu próxima cita es el " + fecha + ": " + cita.titulo() + ".\n\n"
                + cita.descripcion() + "\n\nResponsable: " + cita.responsable()
                + ". Puedes verla y revisar las demás en tu Historial.";
    }

    private String riesgoEstudiante(PerfilEstudiante p) {
        return "Tu riesgo actual es " + p.riesgoActual().toLowerCase(Locale.ROOT) + " (puntaje "
                + p.riesgoScore() + "). Empezaste en " + p.inicio().riesgo().toLowerCase(Locale.ROOT)
                + " (" + p.inicio().riesgoScore() + ") en " + p.inicio().periodo() + " y hoy estás en "
                + p.actual().riesgo().toLowerCase(Locale.ROOT) + " (" + p.actual().riesgoScore() + ").\n\n"
                + p.lectura() + "\n\nNo es una etiqueta: es una señal para acompañarte a tiempo. "
                + "Si algo se está complicando, cuéntamelo y lo revisamos juntos.";
    }

    private String historialEstudiante(PerfilEstudiante p) {
        StringBuilder sb = new StringBuilder("Tu recorrido por semestre:\n");
        for (PeriodoAcademico periodo : p.periodos()) {
            sb.append("• ").append(periodo.periodo()).append(" · ").append(periodo.etiqueta())
                    .append(": promedio ").append(String.format(Locale.US, "%.1f", periodo.promedio()))
                    .append(", ").append(periodo.faltas()).append(" falta(s), riesgo ")
                    .append(periodo.riesgo().toLowerCase(Locale.ROOT)).append(".\n");
        }
        sb.append("\nHoy estás en ").append(p.semestre()).append(".º semestre, grupo ")
                .append(p.grupo()).append(", y tu promedio actual es ")
                .append(String.format(Locale.US, "%.1f", p.promedioActual())).append(".");
        return sb.toString();
    }

    private String resumenEstudiante(PerfilEstudiante p) {
        return "Estás en " + p.semestre() + ".º semestre de " + p.carrera() + " (grupo " + p.grupo()
                + ") en la sede " + p.sede() + ". Tu promedio actual es "
                + String.format(Locale.US, "%.1f", p.promedioActual()) + ", tu riesgo es "
                + p.riesgoActual().toLowerCase(Locale.ROOT) + " y registras " + p.faltas()
                + " falta(s).\n\nPuedo ayudarte a entender tus materias, justificar una falta o "
                + "pensar cómo organizarte. Recuerda que esta conversación es confidencial: solo tú "
                + "puedes ver este historial.";
    }

    /** Rechaza consultas por fuera de la sede, el programa o los semestres del usuario. */
    private String validarAlcance(Usuario usuario, String texto) {
        String t = texto.toLowerCase(Locale.ROOT);
        if ("RECTOR".equals(usuario.rol())) {
            return null;
        }
        String sede = Alcance.sede(usuario, null);
        String programa = Alcance.carrera(usuario, null);
        String facultad = Alcance.facultad(usuario, null);

        if (!texto(facultad)) {
            for (String otra : estudiantes.facultades()) {
                if (!otra.equalsIgnoreCase(facultad) && t.contains(otra.toLowerCase(Locale.ROOT))) {
                    return "No puedo mostrarte información de " + otra + ". Tu cuenta solo tiene "
                            + "permiso sobre " + facultad + ".";
                }
            }
        }

        if (!texto(programa)) {
            for (String otro : estudiantes.programas()) {
                if (!otro.equalsIgnoreCase(programa) && t.contains(otro.toLowerCase(Locale.ROOT))) {
                    return "No puedo mostrarte información de " + otro + ". Tu cuenta solo tiene "
                            + "permiso sobre " + programa + ".";
                }
            }
            if (t.contains("otro programa") || t.contains("otros programas")
                    || t.contains("todas las carreras") || t.contains("nacional")) {
                return "Tu rol no permite comparar programas. Puedo ayudarte con " + programa + ".";
            }
        }
        if (!texto(sede)) {
            for (String otra : estudiantes.sedes()) {
                if (!otra.equalsIgnoreCase(sede) && t.contains(otra.toLowerCase(Locale.ROOT))) {
                    return "No tengo permiso para consultar la sede " + otra + ". Tu alcance es "
                            + sede + ".";
                }
            }
            if (t.contains("otra sede") || t.contains("otras sedes") || t.contains("todas las sedes")
                    || t.contains("nacional") || t.contains("comparar sedes")) {
                return "Solo el rector puede comparar sedes. Tu cuenta está limitada a " + sede + ".";
            }
        }
        return null;
    }

    private String resumen(Reporte r, Usuario usuario) {
        return String.format(Locale.US,
                "En tu alcance hay %s estudiantes: %s en riesgo alto, %s en medio y %s en bajo. "
                        + "El promedio general es %.1f y se acumulan %s faltas. La causa principal es %s.",
                miles(r.totalEstudiantes()), miles(r.riesgoAlto()), miles(r.riesgoMedio()),
                miles(r.riesgoBajo()), r.promedioGeneral(), miles(r.totalFaltas()),
                r.categoriaPrincipal()) + contexto(usuario);
    }

    private String riesgo(Reporte r, Usuario usuario) {
        double pct = r.totalEstudiantes() == 0 ? 0
                : r.riesgoAlto() * 100.0 / r.totalEstudiantes();
        return String.format(Locale.US,
                "El %.1f%% de los %s estudiantes de tu alcance está en riesgo alto (%s personas). "
                        + "Hay %s en riesgo medio y %s retirados. Te recomiendo priorizar los grupos "
                        + "con mayor concentración y revisar las causas de %s.",
                pct, miles(r.totalEstudiantes()), miles(r.riesgoAlto()), miles(r.riesgoMedio()),
                miles(r.retirados()), r.categoriaPrincipal().toLowerCase(Locale.ROOT))
                + contexto(usuario);
    }

    private String faltas(Reporte r, Usuario usuario) {
        double promedio = r.totalEstudiantes() == 0 ? 0
                : (double) r.totalFaltas() / r.totalEstudiantes();
        return String.format(Locale.US,
                "Se registran %s faltas en tu alcance, un promedio de %.1f por estudiante. "
                        + "Las notificaciones de asistencia se agrupan por semestre y grupo para que "
                        + "puedas citar primero a quienes acumulan más inasistencias.",
                miles(r.totalFaltas()), promedio) + contexto(usuario);
    }

    private String grupos(Reporte r) {
        List<Reporte.ConteoGrupo> grupos = new ArrayList<>(r.porGrupo());
        if (grupos.isEmpty()) {
            return "No hay datos de grupos con los filtros actuales.";
        }
        StringBuilder sb = new StringBuilder("Comparación de grupos A y B:\n");
        for (Reporte.ConteoGrupo g : grupos) {
            sb.append("• Grupo ").append(g.grupo()).append(": ").append(miles(g.total()))
                    .append(" estudiantes, ").append(miles(g.alto())).append(" en riesgo alto (")
                    .append(porcentaje(g.alto(), g.total())).append(").\n");
        }
        grupos.sort(Comparator.comparingInt(Reporte.ConteoGrupo::alto).reversed());
        sb.append("El grupo con más riesgo alto es ").append(grupos.getFirst().grupo()).append(".");
        return sb.toString();
    }

    /** Lista los casos que conviene citar primero según el alcance del usuario. */
    private String prioritarios(Usuario usuario) {
        List<Estudiante> top = estudiantes.listar(null, Alcance.carrera(usuario, null),
                Alcance.sede(usuario, null), Alcance.facultad(usuario, null), null,
                Alcance.semestres(usuario), null, null, null, 5);
        if (top.isEmpty()) {
            return "No encuentro estudiantes para citar con el alcance actual.";
        }
        StringBuilder sb = new StringBuilder("Estos son los casos que citaría primero:\n");
        for (Estudiante e : top) {
            sb.append("• ").append(e.nombre()).append(" ").append(e.apellido())
                    .append(" · ").append(e.carrera()).append(", ").append(e.semestre())
                    .append("° semestre, grupo ").append(e.grupo())
                    .append(" · riesgo ").append(e.riesgoActual().toLowerCase(Locale.ROOT))
                    .append(" (").append(e.riesgoScore()).append("/100) y ")
                    .append(e.faltas()).append(" falta(s)")
                    .append(e.razon() == null || e.razon().isBlank()
                            ? ".\n" : "; " + e.razon().replaceAll("\\.$", "") + ".\n");
        }
        sb.append("\nSugiero una conversación breve, registrar el acuerdo en el historial y "
                + "dejar una acción de seguimiento con fecha.");
        sb.append(contexto(usuario));
        return sb.toString();
    }

    private String materias(Usuario usuario, String carrera, String sede, String facultad,
                            Reporte r) {
        List<Estudiante> muestra = estudiantes.listar(null, carrera, sede, facultad, null,
                Alcance.semestres(usuario), null, "ALTO", null, 25);
        List<String> criticas = muestra.stream()
                .flatMap(e -> e.materias().stream())
                .filter(m -> m.nota() < 3.0)
                .map(m -> m.nombre() + " (" + String.format(Locale.US, "%.1f", m.nota()) + ")")
                .distinct().limit(6).toList();
        if (criticas.isEmpty()) {
            return "No veo materias por debajo de 3.0 en la muestra de mayor riesgo. El promedio "
                    + "general de tu alcance es " + String.format(Locale.US, "%.1f", r.promedioGeneral())
                    + "." + contexto(usuario);
        }
        return "Materias con más alertas académicas en tu alcance: " + String.join(", ", criticas)
                + ". Sugiero tutorías focalizadas y revisar si hay faltas pendientes de justificar."
                + contexto(usuario);
    }

    private String sedes(Reporte r, Usuario usuario) {
        if ("RECTOR".equals(usuario.rol())) {
            List<Reporte.ConteoSede> sedes = new ArrayList<>(r.porSede());
            sedes.sort(Comparator.comparingInt(Reporte.ConteoSede::alto).reversed());
            StringBuilder sb = new StringBuilder("Sedes con más riesgo alto:\n");
            sedes.stream().limit(6).forEach(s -> sb.append("• ").append(s.sede()).append(": ")
                    .append(miles(s.alto())).append(" de ").append(miles(s.total())).append("\n"));
            sb.append("Puedes usar la opción General del panel para ver el consolidado nacional.");
            return sb.toString();
        }
        String alcance = "DECANO".equals(usuario.rol())
                ? Alcance.facultad(usuario, null)
                : Alcance.sede(usuario, null);
        return "Tu alcance corresponde a " + alcance + ". Puedo darte el detalle "
                + "de ese alcance, pero no comparar por fuera de él." + contexto(usuario);
    }

    private String semestres(Reporte r) {
        List<Reporte.ConteoSemestre> semestres = r.porSemestre().stream()
                .sorted(Comparator.comparingInt(Reporte.ConteoSemestre::alto).reversed())
                .limit(3).toList();
        StringBuilder sb = new StringBuilder("Semestres con más riesgo alto:\n");
        for (Reporte.ConteoSemestre s : semestres) {
            sb.append("• ").append(s.semestre()).append(".º: ").append(miles(s.alto()))
                    .append(" en alto de ").append(miles(s.total())).append("\n");
        }
        return sb.toString().trim();
    }

    private String contexto(Usuario usuario) {
        return "\n\nAlcance aplicado: " + alcance(usuario) + ".";
    }

    private String alcance(Usuario usuario) {
        return switch (usuario.rol()) {
            case "RECTOR" -> "nacional · todas las sedes y programas";
            case "COORDINADOR_SEDE" -> "sede " + Alcance.sede(usuario, null);
            case "DECANO" -> "facultad " + Alcance.facultad(usuario, null)
                    + " · todas las sedes";
            case "JEFE_PROGRAMA" -> Alcance.carrera(usuario, null) + " · sede "
                    + Alcance.sede(usuario, null);
            case "PROFESOR" -> Alcance.carrera(usuario, null) + " · sede "
                    + Alcance.sede(usuario, null) + " · semestres "
                    + (Alcance.semestres(usuario) == null ? "todos" : Alcance.semestres(usuario));
            default -> usuario.rol();
        };
    }

    private List<String> sugerencias(Usuario usuario) {
        if ("PROFESOR".equals(usuario.rol())) {
            return SUGERENCIAS_PROFESOR;
        }
        List<String> lista = new ArrayList<>(SUGERENCIAS_BASE);
        switch (usuario.rol()) {
            case "RECTOR" -> {
                lista.add("Compara las sedes del país");
                lista.add("¿Cómo están los programas por facultad?");
            }
            case "COORDINADOR_SEDE", "DECANO" -> lista.add("¿Cómo están los programas de mi sede?");
            case "JEFE_PROGRAMA" -> lista.add("¿Cómo van los grupos de mi programa?");
            default -> lista.add("¿Qué estudiantes debo citar esta semana?");
        }
        return List.copyOf(lista);
    }

    private static String porcentaje(int parte, int total) {
        return total == 0 ? "0%" : String.format(Locale.US, "%.1f%%", parte * 100.0 / total);
    }

    private static String miles(int valor) {
        return String.format(Locale.US, "%,d", valor).replace(',', '.');
    }

    private static boolean texto(String valor) {
        return valor == null || valor.isBlank();
    }

    private static boolean contiene(String texto, String... claves) {
        for (String clave : claves) {
            if (texto.contains(clave)) {
                return true;
            }
        }
        return false;
    }
}
