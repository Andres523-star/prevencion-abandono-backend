package co.edu.permanencia.demo.risk;

import co.edu.permanencia.demo.students.Estudiante;
import co.edu.permanencia.demo.students.EstudianteService;
import co.edu.permanencia.demo.students.NotaMateria;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Genera el plan de acción que el profesor ve al hacer clic en "Analizar con IA".
 *
 * <p>Si existe ANTHROPIC_API_KEY, el párrafo se redacta con Claude; si no, se usa un motor local
 * con reglas por categoría. En ambos casos se envían solo datos académicos y la causa, nunca la
 * cédula ni el correo.</p>
 */
@Service
public class AnalisisService {

    private static final Logger log = LoggerFactory.getLogger(AnalisisService.class);

    private final EstudianteService estudiantes;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final String modelo;
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public AnalisisService(EstudianteService estudiantes, ObjectMapper mapper,
                           @Value("${demo.ai.api-key:}") String apiKey,
                           @Value("${demo.ai.model:claude-sonnet-4-5}") String modelo,
                           @Value("${demo.ai.base-url:https://api.anthropic.com}") String baseUrl) {
        this.estudiantes = estudiantes;
        this.mapper = mapper;
        this.apiKey = apiKey;
        this.modelo = modelo;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public AnalisisResponse analizar(int estudianteId) {
        Estudiante e = estudiantes.porId(estudianteId);
        List<String> acciones = generarAcciones(e);
        String recomendacion = generarParrafo(e, acciones);
        String fuente = "Motor de recomendaciones local";

        if (apiKey != null && !apiKey.isBlank()) {
            try {
                String claude = pedirAClaude(e, acciones);
                if (claude != null && !claude.isBlank()) {
                    recomendacion = claude;
                    fuente = "Claude · " + modelo;
                }
            } catch (Exception ex) {
                log.warn("No se pudo usar Claude, se mantiene el motor local: {}", ex.getMessage());
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

    private String pedirAClaude(Estudiante e, List<String> acciones) throws Exception {
        String prompt = """
                Eres un asesor de permanencia universitaria. Redacta en español un párrafo breve
                (máximo 120 palabras) para un profesor que debe acompañar a un estudiante.
                No inventes datos ni uses el nombre del estudiante. Incluye tres pasos concretos.

                Datos del caso:
                - Riesgo: %s (score %d/100)
                - Programa: %s, semestre %d
                - Promedio: %.1f; faltas: %d
                - Categoría: %s
                - Motivo: %s
                - Acciones sugeridas: %s
                """.formatted(e.riesgoActual(), e.riesgoScore(), e.carrera(), e.semestre(), e.promedio(),
                e.faltas(), e.categoria(), e.razon(), String.join("; ", acciones));

        Map<String, Object> cuerpo = Map.of(
                "model", modelo,
                "max_tokens", 600,
                "messages", List.of(Map.of("role", "user", "content", prompt)));

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/messages"))
                .timeout(Duration.ofSeconds(25))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(cuerpo)))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            log.warn("Claude respondió con estado {}", response.statusCode());
            return null;
        }
        JsonNode root = mapper.readTree(response.body());
        JsonNode contenido = root.path("content");
        if (contenido.isArray() && !contenido.isEmpty()) {
            String texto = contenido.get(0).path("text").asText("");
            return texto.isBlank() ? null : texto.trim();
        }
        return null;
    }
}
