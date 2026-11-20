package co.edu.permanencia.demo.risk;

import co.edu.permanencia.demo.students.Estudiante;
import co.edu.permanencia.demo.students.EstudianteService;
import co.edu.permanencia.demo.students.NotaMateria;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Genera el plan de acción que el profesor ve al hacer clic en "Analizar con IA".
 *
 * <p>Si existe ANTHROPIC_API_KEY, el párrafo se redacta con Claude (Sonnet 5.5 por defecto, vía
 * {@link ClienteClaude}); si no, se usa un motor local con reglas por categoría.</p>
 *
 * <p>Privacidad (regla 2 del proyecto): a la IA llegan solo datos académicos por lista blanca
 * ({@link #datosParaIa}): nivel, puntaje, programa, semestre, promedio, faltas, categoría y las
 * acciones sugeridas. Nunca el nombre, la cédula, el correo, el teléfono ni la sede. El motivo
 * (texto libre) solo se envía si {@code demo.ai.free-text-enabled=true}, y redactado.</p>
 */
@Service
public class AnalisisService {

    private final EstudianteService estudiantes;
    private final ClienteClaude claude;
    private final boolean textoLibre;

    public AnalisisService(EstudianteService estudiantes, ClienteClaude claude,
                           @Value("${demo.ai.free-text-enabled:false}") boolean textoLibre) {
        this.estudiantes = estudiantes;
        this.claude = claude;
        this.textoLibre = textoLibre;
    }

    public AnalisisResponse analizar(int estudianteId) {
        Estudiante e = estudiantes.porId(estudianteId);
        List<String> acciones = generarAcciones(e);
        String recomendacion = generarParrafo(e, acciones);
        String fuente = "Motor de recomendaciones local";

        if (claude.disponible()) {
            Optional<String> redactado = claude.redactar(datosParaIa(e, acciones, textoLibre));
            if (redactado.isPresent()) {
                recomendacion = redactado.get();
                fuente = "Claude · " + claude.modelo();
            }
        }

        return new AnalisisResponse(e.id(), e.nombre() + " " + e.apellido(), e.riesgoActual(),
                e.riesgoScore(), e.promedio(), e.faltas(), e.categoria(), recomendacion, acciones, fuente,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")));
    }

    private List<String> generarAcciones(Estudiante e) {
        List<String> acciones = new ArrayList<>(switch (e.categoria()) {
            case "Crisis económica" -> List.of(
                    "Agendar una cita con la oficina de becas y ayudas financieras.",
                    "Revisar opciones de matrícula cero, crédito condonable o apoyo de transporte.",
                    "Contactar al acudiente para explicarle las alternativas de pago.",
                    "Hacer seguimiento semanal de la asistencia y del promedio.");
            case "Depresión familiar" -> List.of(
                    "Remitir al estudiante a psicología y bienestar universitario.",
                    "Agendar una entrevista privada para escuchar su situación familiar.",
                    "Activar el protocolo de acompañamiento con confidencialidad.",
                    "Coordinar un seguimiento cada ocho días con el estudiante.");
            case "Desconexión al futuro" -> List.of(
                    "Agendar una tutoría vocacional con la coordinación del programa.",
                    "Conectar al estudiante con egresados y profesionales del sector.",
                    "Revisar el plan de estudios y las salidas ocupacionales de la carrera.",
                    "Ofrecer una charla de orientación sobre cambio de programa si lo necesita.");
            case "Estrés académico" -> List.of(
                    "Agendar una tutoría académica en las materias con menor nota.",
                    "Recomendar un taller de hábitos de estudio y manejo del tiempo.",
                    "Revisar la carga académica y priorizar las entregas del semestre.",
                    "Remitir a bienestar si la ansiedad persiste.");
            default -> List.of(
                    "Proponer un proyecto práctico o un semillero relacionado con su carrera.",
                    "Asignar un mentor docente para acompañar su proceso.",
                    "Conectar sus intereses con actividades extracurriculares.",
                    "Revisar expectativas y metas a corto plazo.");
        });

        NotaMateria masBaja = e.materias().stream()
                .min(Comparator.comparingDouble(NotaMateria::nota)).orElse(null);
        if (e.promedio() < 3.2 && masBaja != null) {
            acciones.add(String.format(Locale.US, "Inscribir en monitoría de %s (nota actual %.1f).",
                    masBaja.nombre(), masBaja.nota()));
        }
        if (e.faltas() >= 4) {
            acciones.add("Revisar las faltas acumuladas y las que aún se pueden justificar este semestre.");
        }
        if ("ALTO".equals(e.riesgoActual())) {
            acciones.add("Registrar el caso en el comité de permanencia y programar seguimiento en 7 días.");
        }
        return acciones.size() > 7 ? List.copyOf(acciones.subList(0, 7)) : List.copyOf(acciones);
    }

    private String generarParrafo(Estudiante e, List<String> acciones) {
        String nivel = switch (e.riesgoActual()) {
            case "ALTO" -> "riesgo alto de abandonar";
            case "MEDIO" -> "riesgo medio";
            default -> "riesgo bajo, aunque conviene acompañarlo";
        };
        String pasos = String.join(" ", java.util.stream.IntStream.range(0, Math.min(3, acciones.size()))
                .mapToObj(i -> (i + 1) + ") " + acciones.get(i)).toList());
        return String.format(Locale.US,
                "El estudiante está en %s (score %d/100). Su promedio es %.1f y acumula %d faltas; "
                        + "la categoría que mejor explica la situación es %s. Motivo reportado: %s "
                        + "Plan de acción sugerido: %s El resto de acciones está en la lista de abajo.",
                nivel, e.riesgoScore(), e.promedio(), e.faltas(), e.categoria().toLowerCase(Locale.ROOT),
                e.razon(), pasos);
    }

    /**
     * Datos del caso para la IA, armados por lista blanca de campos (nunca por lista negra).
     *
     * <p>Las acciones sugeridas solo mencionan materias y servicios de la universidad. El motivo
     * es texto libre escrito por personas: por defecto no se envía, y si se habilita pasa por
     * {@link RedactorPii} (correos, teléfonos, documentos, enlaces y el nombre del estudiante).</p>
     */
    static String datosParaIa(Estudiante e, List<String> acciones, boolean incluirTextoLibre) {
        StringBuilder sb = new StringBuilder("Datos del caso (seudonimizados):\n");
        sb.append(String.format(Locale.US, "- Riesgo: %s (puntaje %d/100)%n", e.riesgoActual(), e.riesgoScore()));
        sb.append(String.format(Locale.US, "- Programa: %s, semestre %d%n", e.carrera(), e.semestre()));
        sb.append(String.format(Locale.US, "- Promedio: %.1f; faltas: %d%n", e.promedio(), e.faltas()));
        sb.append(String.format(Locale.US, "- Categoría principal: %s%n", e.categoria()));
        if (incluirTextoLibre) {
            String motivo = RedactorPii.redactar(e.razon(), e.nombre(), e.apellido());
            if (!motivo.isBlank()) {
                sb.append("- Motivo reportado (redactado): ").append(motivo).append('\n');
            }
        }
        sb.append("- Acciones sugeridas: ").append(String.join("; ", acciones));
        return sb.toString();
    }
}
