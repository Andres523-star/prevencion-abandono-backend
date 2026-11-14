package co.edu.permanencia.demo.justificaciones;

import co.edu.permanencia.demo.auth.Usuario;
import co.edu.permanencia.demo.common.Alcance;
import co.edu.permanencia.demo.common.ApiException;
import co.edu.permanencia.demo.students.Estudiante;
import co.edu.permanencia.demo.students.EstudianteService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Radicación de justificaciones de falta.
 *
 * <p>Valida que la evidencia esté adjunta, registra el radicado y deja la falta como justificada.
 * En el sistema real este flujo dispara un correo al docente; aquí se guarda el destinatario y se
 * responde con el mensaje que ve el estudiante.</p>
 */
@Service
public class JustificacionService {

    private static final String CORREO_DEFECTO = "bienestar.pasto@ucc.edu.co";

    private final EstudianteService estudiantes;
    private final List<Justificacion> justificaciones = new CopyOnWriteArrayList<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    public JustificacionService(EstudianteService estudiantes) {
        this.estudiantes = estudiantes;
    }

    public Justificacion crear(Usuario usuario, JustificacionRequest request) {
        Integer estudianteId = Alcance.estudianteId(usuario);
        if (estudianteId == null) {
            throw ApiException.forbidden("Solo un estudiante puede radicar una justificación");
        }
        if (request == null || request.materia() == null || request.materia().isBlank()) {
            throw ApiException.badRequest("Selecciona la materia de la falta");
        }
        if (request.fechaFalta() == null || request.fechaFalta().isBlank()) {
            throw ApiException.badRequest("Indica la fecha de la falta");
        }
        if (request.motivo() == null || request.motivo().isBlank()) {
            throw ApiException.badRequest("Selecciona el motivo de la falta");
        }
        if (request.evidencia() == null || request.evidencia().isBlank()) {
            throw ApiException.badRequest("Recuerda agregar la foto de evidencia");
        }
        String correo = request.correoDestino() == null || request.correoDestino().isBlank()
                ? CORREO_DEFECTO : request.correoDestino().trim();
        if (!correo.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw ApiException.badRequest("El correo de envío no es válido");
        }

        Estudiante e = estudiantes.porId(estudianteId);
        int id = nextId.getAndIncrement();
        String radicado = String.format("JUS-%d-%04d", LocalDateTime.now().getYear(), id);
        Justificacion justificacion = new Justificacion(id, e.id(),
                e.nombre() + " " + e.apellido(), request.materia().trim(),
                request.semestre() == null ? e.semestre() : request.semestre(),
                request.grupo() == null || request.grupo().isBlank() ? e.grupo() : request.grupo(),
                request.fechaFalta(), request.motivo().trim(),
                request.descripcion() == null ? "" : request.descripcion().trim(),
                request.evidencia(), correo, "EN_REVISION", radicado,
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        justificaciones.add(0, justificacion);
        estudiantes.justificarFalta(e.id(), request.materia().trim(), justificacion.semestre());
        return justificacion;
    }

    /** El estudiante ve solo sus radicados; los roles institucionales ven los de su alcance. */
    public List<Justificacion> listar(Usuario usuario) {
        if (usuario == null) {
            return List.of();
        }
        if ("ESTUDIANTE".equals(usuario.rol())) {
            Integer id = Alcance.estudianteId(usuario);
            return justificaciones.stream()
                    .filter(j -> id != null && j.estudianteId() == id).toList();
        }
        return justificaciones.stream().filter(j -> {
            try {
                Estudiante e = estudiantes.porId(j.estudianteId());
                return Alcance.cubre(usuario, e.id(), e.carrera(), e.facultad(), e.sede(), e.semestre());
            } catch (RuntimeException ex) {
                return false;
            }
        }).toList();
    }

    public String correoSugerido(Usuario usuario) {
        Integer id = Alcance.estudianteId(usuario);
        if (id == null) {
            return CORREO_DEFECTO;
        }
        Estudiante e = estudiantes.porId(id);
        return "bienestar." + e.sede().toLowerCase(Locale.ROOT).replace(" ", "") + "@ucc.edu.co";
    }
}
