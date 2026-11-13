package co.edu.permanencia.demo.notifications;

import co.edu.permanencia.demo.auth.Usuario;
import co.edu.permanencia.demo.common.Alcance;
import co.edu.permanencia.demo.common.ApiException;
import co.edu.permanencia.demo.students.Estudiante;
import co.edu.permanencia.demo.students.EstudianteService;
import co.edu.permanencia.demo.students.NotaMateria;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Campanita personalizada por usuario.
 *
 * <p>No existe una única lista global: cada cuenta recibe sus propias notificaciones según su rol
 * y su alcance. El profesor solo ve sus semestres y su sede; el jefe de programa, su programa; el
 * coordinador y el decano, su sede; el rector, todo el país. El estudiante recibe únicamente
 * alertas sobre su propio proceso académico.</p>
 *
 * <p>El estado de leída se guarda por correo, de modo que dos usuarios que abran la campanita al
 * mismo tiempo no comparten el mismo contador.</p>
 */
@Service
public class NotificacionService {

    private final EstudianteService estudiantes;
    private final Map<String, Set<Integer>> leidas = new ConcurrentHashMap<>();

    public NotificacionService(EstudianteService estudiantes) {
        this.estudiantes = estudiantes;
    }

    /** Notificaciones del usuario autenticado, con filtro opcional por grupo A/B. */
    public List<Notificacion> listar(Usuario usuario, String grupo) {
        List<Notificacion> base = new ArrayList<>();
        if (usuario == null) {
            return base;
        }
        if ("ESTUDIANTE".equals(usuario.rol())) {
            base.addAll(personales(usuario));
        } else {
            base.addAll(institucionales(usuario, grupo));
        }
        Set<Integer> leidos = leidas.getOrDefault(clave(usuario), Set.of());
        for (Notificacion n : base) {
            n.setLeida(leidos.contains(n.getId()));
        }
        return List.copyOf(base);
    }

    public void marcarLeida(Usuario usuario, int id) {
        if (listar(usuario, null).stream().noneMatch(n -> n.getId() == id)) {
            throw ApiException.notFound("Notificación no encontrada");
        }
        leidas.computeIfAbsent(clave(usuario), k -> ConcurrentHashMap.newKeySet()).add(id);
    }

    public void marcarTodas(Usuario usuario) {
        Set<Integer> conjunto = leidas.computeIfAbsent(clave(usuario),
                k -> ConcurrentHashMap.newKeySet());
        listar(usuario, null).forEach(n -> conjunto.add(n.getId()));
    }

    public long noLeidas(Usuario usuario) {
        return listar(usuario, null).stream().filter(n -> !n.isLeida()).count();
    }

    /** Alertas propias del estudiante: notas, faltas y acompañamiento. */
    private List<Notificacion> personales(Usuario usuario) {
        List<Notificacion> lista = new ArrayList<>();
        Integer id = usuario.estudianteId();
        if (id == null) {
            return lista;
        }
        Estudiante e = estudiantes.porId(id);

        lista.add(construir(1, e, "ACADEMICO", "Tu promedio del período",
                String.format(Locale.US, "Promedio actual %.1f · riesgo %s", e.promedio(),
                        e.riesgoActual().toLowerCase(Locale.ROOT)), -12));

        int pendientes = e.materias().stream().mapToInt(NotaMateria::faltasPendientes).sum();
        if (pendientes > 0 || e.faltas() > 0) {
            int total = Math.max(pendientes, e.faltas());
            lista.add(construir(2, e, "ASISTENCIA", "Faltas por justificar",
                    String.format(Locale.US, "Tienes %d falta(s) sin evidencia. Entra a tus materias "
                            + "y adjunta la foto del soporte.", total), -95));
        }

        e.materias().stream().filter(NotaMateria::homologada).findFirst().ifPresent(m ->
                lista.add(construir(3, e, "MATERIA", "Materia homologada",
                        m.nombre() + " fue reconocida desde " + m.semestre() + ".º semestre, grupo "
                                + m.grupo() + ".", -240)));

        lista.add(construir(4, e, "CITA", "Próxima cita de acompañamiento",
                "Tu tutor revisará contigo el plan de nivelación esta semana.", -1440));
        lista.add(construir(5, e, "MATERIA", "Nueva nota publicada",
                "Ya puedes consultar la última nota de " + e.materias().getFirst().nombre() + ".",
                -2880));
        return lista;
    }

    /** Alertas de los estudiantes que el rol puede acompañar. */
    private List<Notificacion> institucionales(Usuario usuario, String grupo) {
        List<Notificacion> lista = new ArrayList<>();
        List<Estudiante> candidatos = estudiantes.listar(null, Alcance.carrera(usuario, null),
                Alcance.sede(usuario, null), Alcance.facultad(usuario, null), null,
                Alcance.semestres(usuario), null, null, null, grupo, 14);

        if ("RECTOR".equals(usuario.rol())) {
            lista.add(new Notificacion(9001, 0, "Vista nacional", "Todas las sedes", 0, "",
                    "Institucional", "MEDIO", "RESUMEN", "Panorama general de sedes",
                    "El rector puede abrir la opción General para comparar las 17 sedes.",
                    "", LocalDateTime.now().minusMinutes(30).toString(), false));
        }

        int i = 0;
        for (Estudiante e : candidatos) {
            String tipo = switch (i % 3) {
                case 0 -> "RIESGO";
                case 1 -> "ASISTENCIA";
                default -> "ACADEMICO";
            };
            String titulo = switch (tipo) {
                case "RIESGO" -> "Riesgo " + e.riesgoActual().toLowerCase(Locale.ROOT)
                        + " en " + e.semestre() + ".º " + e.grupo();
                case "ASISTENCIA" -> "Faltas acumuladas · grupo " + e.grupo();
                default -> "Promedio bajo · grupo " + e.grupo();
            };
            String motivo = String.format(Locale.US,
                    "Promedio %.1f · %d faltas · %s · %s %s", e.promedio(), e.faltas(),
                    e.categoria(), e.semestre() + ".º semestre,", "grupo " + e.grupo());
            lista.add(construir(100 + i, e, tipo, titulo, motivo, -(i * 37L) - 5));
            i++;
        }
        return lista;
    }

    private Notificacion construir(int id, Estudiante e, String tipo, String titulo, String motivo,
                                   long minutos) {
        return new Notificacion(id, e.id(), e.nombre() + " " + e.apellido(), e.carrera(), e.semestre(),
                e.grupo(), e.categoria(), e.riesgoActual(), tipo, titulo, motivo, enlaceCita(e),
                LocalDateTime.now().plusMinutes(minutos).toString(), false);
    }

    private static String clave(Usuario usuario) {
        return usuario.email() == null ? "anonimo" : usuario.email().toLowerCase(Locale.ROOT);
    }

    private String enlaceCita(Estudiante estudiante) {
        String titulo = "Cita de acompañamiento con " + estudiante.nombre() + " " + estudiante.apellido();
        String detalle = "Riesgo " + estudiante.riesgoActual() + " · " + estudiante.categoria()
                + " · " + estudiante.carrera() + " · grupo " + estudiante.grupo();
        LocalDateTime inicio = LocalDateTime.now().plusDays(1).withHour(10).withMinute(0)
                .withSecond(0).withNano(0);
        DateTimeFormatter formato = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
        String fechas = formato.format(inicio) + "/" + formato.format(inicio.plusMinutes(30));
        return "https://calendar.google.com/calendar/render?action=TEMPLATE&text="
                + URLEncoder.encode(titulo, StandardCharsets.UTF_8)
                + "&details=" + URLEncoder.encode(detalle, StandardCharsets.UTF_8)
                + "&dates=" + fechas;
    }
}
