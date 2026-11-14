package co.edu.permanencia.demo.acompanamiento;

import co.edu.permanencia.demo.common.ApiException;
import co.edu.permanencia.demo.students.EstudianteService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Historial de acompañamiento en memoria.
 *
 * <p>Cada estudiante recibe una historia determinista (según su id) con citas pasadas ya
 * cumplidas o no asistidas y una cita pendiente. El docente puede cambiar el estado de cada
 * acción desde la ficha.</p>
 */
@Service
public class AcompanamientoService {

    private static final List<String> ESTADOS = List.of("CUMPLIDO", "NO_ASISTIO", "PENDIENTE");

    private static final String[][] ACCIONES = {
            {"Cita con psicología", "Espacio de escucha y orientación emocional.", "Bienestar Universitario"},
            {"Tutoría académica", "Refuerzo en las materias con mayor dificultad.", "Docente del programa"},
            {"Taller de hábitos de estudio", "Organización del tiempo y técnicas de estudio.", "Bienestar Universitario"},
            {"Llamada de seguimiento", "Contacto telefónico para conocer cómo avanza el caso.", "Coordinación académica"},
            {"Actividad deportiva", "Encuentro recreativo para fortalecer el vínculo con la universidad.", "Deportes UCC"},
            {"Sesión con trabajo social", "Revisión de la situación familiar y económica.", "Trabajo Social"},
            {"Cita con bienestar universitario", "Acompañamiento integral y remisión a apoyos.", "Bienestar Universitario"},
            {"Apoyo económico", "Orientación sobre becas, créditos y auxilios.", "Oficina de becas"}
    };

    private final EstudianteService estudiantes;
    private final Map<Integer, List<AccionAcompanamiento>> historiales = new ConcurrentHashMap<>();

    public AcompanamientoService(EstudianteService estudiantes) {
        this.estudiantes = estudiantes;
    }

    public List<AccionAcompanamiento> de(int estudianteId) {
        estudiantes.porId(estudianteId);
        return List.copyOf(historiales.computeIfAbsent(estudianteId, this::generar));
    }

    public AccionAcompanamiento actualizar(int estudianteId, int accionId, String estado) {
        String nuevo = estado == null ? "" : estado.trim().toUpperCase(Locale.ROOT);
        if (!ESTADOS.contains(nuevo)) {
            throw ApiException.badRequest("El estado debe ser CUMPLIDO, NO_ASISTIO o PENDIENTE");
        }
        List<AccionAcompanamiento> acciones = new ArrayList<>(de(estudianteId));
        for (int i = 0; i < acciones.size(); i++) {
            AccionAcompanamiento accion = acciones.get(i);
            if (accion.id() == accionId) {
                AccionAcompanamiento actualizada = new AccionAcompanamiento(accion.id(),
                        accion.estudianteId(), accion.fecha(), accion.titulo(), accion.descripcion(),
                        accion.responsable(), nuevo);
                acciones.set(i, actualizada);
                historiales.put(estudianteId, List.copyOf(acciones));
                return actualizada;
            }
        }
        throw ApiException.notFound("La acción de acompañamiento no existe");
    }

    /** Historial curado de la estudiante demo: más de un año de acciones y citas próximas. */
    private List<AccionAcompanamiento> historialEstudianteDemo() {
        int id = EstudianteService.ESTUDIANTE_DEMO_ID;
        LocalDate hoy = LocalDate.now();
        return List.of(
                accion(id, 1, LocalDate.of(2024, 8, 6), "Inducción y bienvenida",
                        "Primer contacto al ingresar al programa; se identifica riesgo económico familiar.",
                        "Bienestar Universitario", "CUMPLIDO"),
                accion(id, 2, LocalDate.of(2024, 10, 9), "Remisión a bienestar",
                        "Alerta temprana por bajo rendimiento y faltas durante el primer corte.",
                        "Coordinación académica", "CUMPLIDO"),
                accion(id, 3, LocalDate.of(2024, 11, 27), "Cita con psicología",
                        "Acompañamiento emocional por la carga familiar y económica del primer semestre.",
                        "Bienestar Universitario", "NO_ASISTIO"),
                accion(id, 4, LocalDate.of(2025, 2, 12), "Cita con bienestar universitario",
                        "Primer encuentro para conocer la situación económica y emocional de Mariana.",
                        "Bienestar Universitario", "CUMPLIDO"),
                accion(id, 5, LocalDate.of(2025, 3, 5), "Apoyo económico",
                        "Orientación y radicación de la solicitud de beca y auxilio de transporte.",
                        "Oficina de becas", "CUMPLIDO"),
                accion(id, 6, LocalDate.of(2025, 4, 9), "Taller de hábitos de estudio",
                        "Organización del tiempo y técnicas de estudio para recuperar materias.",
                        "Bienestar Universitario", "CUMPLIDO"),
                accion(id, 7, LocalDate.of(2025, 5, 21), "Llamada de seguimiento",
                        "Contacto telefónico para revisar avances; no fue posible ubicar a la estudiante.",
                        "Coordinación académica", "NO_ASISTIO"),
                accion(id, 8, LocalDate.of(2025, 8, 14), "Tutoría académica",
                        "Refuerzo en Cálculo y Bases de Datos con el monitor del programa.",
                        "Docente del programa", "CUMPLIDO"),
                accion(id, 9, LocalDate.of(2025, 9, 18), "Cita con psicología",
                        "Espacio de escucha y manejo del estrés durante los parciales.",
                        "Bienestar Universitario", "CUMPLIDO"),
                accion(id, 10, LocalDate.of(2025, 11, 6), "Sesión con trabajo social",
                        "Revisión de la situación familiar y articulación con la oficina de becas.",
                        "Trabajo Social", "CUMPLIDO"),
                accion(id, 11, LocalDate.of(2026, 2, 10), "Tutoría académica",
                        "Acompañamiento en Estructuras de Datos y preparación del primer corte.",
                        "Docente del programa", "CUMPLIDO"),
                accion(id, 12, LocalDate.of(2026, 4, 22), "Actividad deportiva",
                        "Encuentro recreativo para fortalecer el vínculo con la universidad.",
                        "Deportes UCC", "CUMPLIDO"),
                accion(id, 13, LocalDate.of(2026, 6, 3), "Cita con bienestar universitario",
                        "Seguimiento del semestre; la estudiante no pudo asistir por su horario laboral.",
                        "Bienestar Universitario", "NO_ASISTIO"),
                accion(id, 14, LocalDate.of(2026, 8, 19), "Taller de hábitos de estudio",
                        "Plan de entregas y preparación de parciales para el semestre actual.",
                        "Bienestar Universitario", "CUMPLIDO"),
                accion(id, 15, LocalDate.of(2026, 10, 15), "Llamada de seguimiento",
                        "Revisión del avance académico: promedio de 3.9 y riesgo medio.",
                        "Coordinación académica", "CUMPLIDO"),
                accion(id, 16, hoy.plusDays(5), "Cita con tu profesor",
                        "Revisión del proyecto de Pruebas de Software y plan del último corte.",
                        "Carlos Andrés Muñoz", "PENDIENTE"),
                accion(id, 17, hoy.plusDays(19), "Seguimiento académico",
                        "Encuentro con bienestar para cerrar el semestre y planear el próximo.",
                        "Bienestar Universitario", "PENDIENTE"));
    }

    private AccionAcompanamiento accion(int estudianteId, int consecutivo, LocalDate fecha, String titulo,
            String descripcion, String responsable, String estado) {
        return new AccionAcompanamiento((estudianteId * 100) + consecutivo, estudianteId, fecha.toString(),
                titulo, descripcion, responsable, estado);
    }

    private List<AccionAcompanamiento> generar(int estudianteId) {
        if (estudianteId == EstudianteService.ESTUDIANTE_DEMO_ID) {
            return historialEstudianteDemo();
        }
        Random rnd = new Random(estudianteId * 31L + 2026);
        LocalDate hoy = LocalDate.now();
        // Siete acciones pasadas repartidas en las últimas seis semanas.
        int[] diasAtras = {42, 35, 28, 21, 14, 7, 3};
        List<AccionAcompanamiento> acciones = new ArrayList<>();
        for (int i = 0; i < diasAtras.length; i++) {
            String[] base = ACCIONES[(estudianteId + i) % ACCIONES.length];
            String estado = rnd.nextInt(10) < 7 ? "CUMPLIDO" : "NO_ASISTIO";
            acciones.add(new AccionAcompanamiento(
                    (estudianteId * 100) + i + 1,
                    estudianteId,
                    hoy.minusDays(diasAtras[i]).toString(),
                    base[0],
                    base[1],
                    base[2],
                    estado));
        }
        // Una cita futura pendiente de confirmar.
        String[] siguiente = ACCIONES[(estudianteId + diasAtras.length) % ACCIONES.length];
        acciones.add(new AccionAcompanamiento(
                (estudianteId * 100) + diasAtras.length + 1,
                estudianteId,
                hoy.plusDays(4 + rnd.nextInt(6)).toString(),
                siguiente[0],
                siguiente[1],
                siguiente[2],
                "PENDIENTE"));
        return acciones;
    }
}
