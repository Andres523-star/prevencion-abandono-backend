package co.edu.permanencia.demo.chat;

import co.edu.permanencia.demo.auth.Usuario;
import co.edu.permanencia.demo.common.Alcance;
import co.edu.permanencia.demo.common.ApiException;
import co.edu.permanencia.demo.students.Estudiante;
import co.edu.permanencia.demo.students.EstudianteService;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Chat interno de la demo.
 *
 * <p>Los mensajes y adjuntos viven en memoria. Cuando el profesor envía un mensaje, el estudiante
 * responde automáticamente unos segundos después para que la conversación se sienta viva durante
 * la presentación. En un sistema real esto sería un canal de tiempo real con WebSocket.</p>
 */
@Service
public class ChatService {

    private static final long MAX_ADJUNTO = 5L * 1024 * 1024;

    private final EstudianteService estudiantes;
    private final Map<Integer, List<Mensaje>> mensajes = new ConcurrentHashMap<>();
    private final Map<String, Adjunto> adjuntos = new ConcurrentHashMap<>();
    private final Map<Integer, ScheduledFuture<?>> respuestasPendientes = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private final Random random = new Random();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "chat-respuesta-automatica");
        thread.setDaemon(true);
        return thread;
    });

    public ChatService(EstudianteService estudiantes) {
        this.estudiantes = estudiantes;
        sembrarConversaciones();
    }

    public List<Conversacion> conversaciones() {
        return conversaciones(null);
    }

    /** Conversaciones visibles para el usuario según su rol y alcance. */
    public List<Conversacion> conversaciones(Usuario usuario) {
        boolean estudiante = usuario != null && "ESTUDIANTE".equals(usuario.rol());
        List<Conversacion> resultado = new ArrayList<>();
        for (Map.Entry<Integer, List<Mensaje>> entry : mensajes.entrySet()) {
            List<Mensaje> lista = entry.getValue();
            if (lista.isEmpty()) {
                continue;
            }
            Estudiante e = estudiantes.porId(entry.getKey());
            if (!Alcance.cubre(usuario, e.id(), e.carrera(), e.facultad(), e.sede(), e.semestre())) {
                continue;
            }
            Mensaje ultimo = lista.stream().max(Comparator.comparingLong(Mensaje::getTimestamp)).orElseThrow();
            String autorNoLeido = estudiante ? "PROFESOR" : "ESTUDIANTE";
            long noLeidos = lista.stream()
                    .filter(m -> autorNoLeido.equals(m.getAutor()) && !m.isLeido())
                    .count();
            String resumen = "ARCHIVO".equals(ultimo.getTipo())
                    ? "📎 " + (ultimo.getNombreArchivo() == null ? "Archivo" : ultimo.getNombreArchivo())
                    : ultimo.getTexto();
            resultado.add(new Conversacion(e.id(), e.nombre(), e.apellido(), e.carrera(), e.semestre(),
                    e.categoria(), e.riesgoActual(), resumen, ultimo.getFecha(), noLeidos));
        }
        resultado.sort(Comparator.comparing(Conversacion::ultimaFecha).reversed());
        return resultado;
    }

    public List<Mensaje> mensajes(int estudianteId) {
        return mensajes(null, estudianteId);
    }

    /** Mensajes de una conversación, validando que el usuario tenga alcance sobre el estudiante. */
    public List<Mensaje> mensajes(Usuario usuario, int estudianteId) {
        Estudiante estudiante = estudiantes.porId(estudianteId);
        if (!Alcance.cubre(usuario, estudiante.id(), estudiante.carrera(), estudiante.facultad(),
                estudiante.sede(), estudiante.semestre())) {
            throw ApiException.forbidden("No tienes acceso a esta conversación");
        }
        List<Mensaje> lista = mensajes.getOrDefault(estudianteId, List.of());
        String autorContrario = usuario != null && "ESTUDIANTE".equals(usuario.rol())
                ? "PROFESOR" : "ESTUDIANTE";
        lista.stream().filter(m -> autorContrario.equals(m.getAutor())).forEach(m -> m.setLeido(true));
        return lista.stream().sorted(Comparator.comparingLong(Mensaje::getTimestamp)).toList();
    }

    public Mensaje enviar(int estudianteId, MensajeRequest request) {
        return enviar(null, estudianteId, request);
    }

    public Mensaje enviar(Usuario usuario, int estudianteId, MensajeRequest request) {
        Estudiante estudiante = estudiantes.porId(estudianteId);
        if (!Alcance.cubre(usuario, estudiante.id(), estudiante.carrera(), estudiante.facultad(),
                estudiante.sede(), estudiante.semestre())) {
            throw ApiException.forbidden("No tienes acceso a esta conversación");
        }
        boolean esEstudiante = usuario != null && "ESTUDIANTE".equals(usuario.rol());
        String texto = request.texto() == null ? "" : request.texto().trim();
        String tipo = "TEXTO";
        String archivoId = null;
        String nombreArchivo = null;
        String mime = null;

        if (request.archivoBase64() != null && !request.archivoBase64().isBlank()) {
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(request.archivoBase64());
            } catch (IllegalArgumentException ex) {
                throw ApiException.badRequest("El archivo no se pudo leer");
            }
            if (bytes.length > MAX_ADJUNTO) {
                throw ApiException.badRequest("El archivo no puede superar 5 MB");
            }
            // El tipo sale del contenido real, no de lo que declara el navegador.
            ValidadorAdjuntos.Adjunto validado = ValidadorAdjuntos.validar(bytes, request.nombreArchivo());
            archivoId = UUID.randomUUID().toString();
            nombreArchivo = validado.nombre();
            mime = validado.mime();
            adjuntos.put(archivoId, new Adjunto(bytes, nombreArchivo, mime));
            tipo = "ARCHIVO";
        }

        if (texto.isBlank() && archivoId == null) {
            throw ApiException.badRequest("Escribe un mensaje o adjunta un archivo");
        }

        Mensaje mensaje = agregar(estudianteId, esEstudiante ? "ESTUDIANTE" : "PROFESOR", texto, tipo,
                nombreArchivo, mime, archivoId, LocalDateTime.now(), true);
        if (esEstudiante) {
            programarRespuestaProfesor(estudiante);
        } else {
            programarRespuesta(estudiante);
        }
        return mensaje;
    }

    public Adjunto descargar(String archivoId) {
        Adjunto adjunto = adjuntos.get(archivoId);
        if (adjunto == null) {
            throw ApiException.notFound("El archivo ya no está disponible");
        }
        return adjunto;
    }

    private void programarRespuesta(Estudiante estudiante) {
        ScheduledFuture<?> anterior = respuestasPendientes.remove(estudiante.id());
        if (anterior != null) {
            anterior.cancel(false);
        }
        ScheduledFuture<?> futuro = scheduler.schedule(() -> {
            try {
                agregar(estudiante.id(), "ESTUDIANTE", respuestaAutomatica(estudiante), "TEXTO",
                        null, null, null, LocalDateTime.now(), false);
            } finally {
                respuestasPendientes.remove(estudiante.id());
            }
        }, 3, TimeUnit.SECONDS);
        respuestasPendientes.put(estudiante.id(), futuro);
    }

    /** Respuesta automática del profesor cuando quien escribe es la cuenta de estudiante. */
    private void programarRespuestaProfesor(Estudiante estudiante) {
        ScheduledFuture<?> anterior = respuestasPendientes.remove(estudiante.id());
        if (anterior != null) {
            anterior.cancel(false);
        }
        ScheduledFuture<?> futuro = scheduler.schedule(() -> {
            try {
                agregar(estudiante.id(), "PROFESOR", respuestaProfesor(estudiante), "TEXTO",
                        null, null, null, LocalDateTime.now(), false);
            } finally {
                respuestasPendientes.remove(estudiante.id());
            }
        }, 3, TimeUnit.SECONDS);
        respuestasPendientes.put(estudiante.id(), futuro);
    }

    private Mensaje agregar(int estudianteId, String autor, String texto, String tipo, String nombreArchivo,
                            String mime, String archivoId, LocalDateTime fecha, boolean leido) {
        Mensaje mensaje = new Mensaje(nextId.getAndIncrement(), estudianteId, autor, texto, tipo, nombreArchivo,
                mime, archivoId, fecha.toString(),
                fecha.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), leido);
        mensajes.computeIfAbsent(estudianteId, id -> new CopyOnWriteArrayList<>()).add(mensaje);
        return mensaje;
    }

    private void sumarSemilla(List<Estudiante> semilla, java.util.Set<Integer> vistos,
                              List<Estudiante> nuevos) {
        for (Estudiante e : nuevos) {
            if (vistos.add(e.id())) {
                semilla.add(e);
            }
        }
    }

    private void sembrarConversaciones() {
        List<Estudiante> semilla = new ArrayList<>();
        java.util.Set<Integer> vistos = new java.util.HashSet<>();
        // Hilos con mayor riesgo para el rector y los decanos.
        sumarSemilla(semilla, vistos, estudiantes.listar(null, null, null, null, null, "ALTO", 24));
        sumarSemilla(semilla, vistos, estudiantes.listar(null, null, null, null, null, "MEDIO", 8));
        // Al menos unos hilos por sede para que coordinadores y decanos tengan acompañamiento.
        for (String sede : estudiantes.sedes()) {
            sumarSemilla(semilla, vistos, estudiantes.listar(null, null, sede, null, null, "ALTO", 3));
            sumarSemilla(semilla, vistos, estudiantes.listar(null, null, sede, null, null, "MEDIO", 1));
        }
        // El profesor y el jefe de programa demo ven Pasto · Ingeniería de Software.
        sumarSemilla(semilla, vistos,
                estudiantes.listar(null, "Ingeniería de Software", "Pasto", null, null, "ALTO", 6));
        sumarSemilla(semilla, vistos,
                estudiantes.listar(null, "Ingeniería de Software", "Pasto", null, null, "MEDIO", 3));
        // Los semestres del profesor, aunque no estén entre los de mayor riesgo.
        sumarSemilla(semilla, vistos, estudiantes.listar(null, "Ingeniería de Software", "Pasto", null,
                null, List.of(1, 5, 6, 8), null, null, null, 6));
        for (int i = 0; i < semilla.size(); i++) {
            Estudiante e = semilla.get(i);
            // La estudiante demo tiene un hilo curado con su profesor; no se mezcla con el genérico.
            if (e.id() == EstudianteService.ESTUDIANTE_DEMO_ID) {
                continue;
            }
            LocalDateTime base = LocalDateTime.now().minusHours(3).minusMinutes(i * 11L);
            agregar(e.id(), "ESTUDIANTE", "Buenas tardes profe, ¿tiene un momento?", "TEXTO",
                    null, null, null, base, i % 2 == 0);
            agregar(e.id(), "PROFESOR", "Hola " + e.nombre() + ", claro que sí. Estuve revisando tu caso "
                    + "y quiero ayudarte.", "TEXTO", null, null, null, base.plusMinutes(8), true);
            agregar(e.id(), "ESTUDIANTE", respuestaInicial(e), "TEXTO",
                    null, null, null, base.plusMinutes(24), i % 3 != 0);
            if (i % 2 == 0) {
                agregar(e.id(), "PROFESOR", "Gracias por contarme. Vamos a armar un plan juntos y te agendo una cita.",
                        "TEXTO", null, null, null, base.plusMinutes(39), true);
            }
            // Todos los hilos tienen recorrido suficiente para que el chat se pueda desplazar.
            if (i < semilla.size()) {
                agregar(e.id(), "PROFESOR", "Te dejo tres pasos para esta semana: 1) no faltar a clase, "
                                + "2) hablar con tu director de programa y 3) venir a bienestar universitario.",
                        "TEXTO", null, null, null, base.plusMinutes(52), true);
                agregar(e.id(), "ESTUDIANTE", "Sí profe, gracias. ¿El martes a qué hora puedo pasar?",
                        "TEXTO", null, null, null, base.plusMinutes(66), i % 2 == 0);
                agregar(e.id(), "PROFESOR", "El martes a las 10:00 te espero en coordinación. "
                                + "Te va a acompañar también trabajo social.",
                        "TEXTO", null, null, null, base.plusMinutes(79), true);
                agregar(e.id(), "ESTUDIANTE", "Perfecto profe, ahí estaré. Gracias por no soltarme.",
                        "TEXTO", null, null, null, base.plusMinutes(92), true);
                if (i < 8) {
                    agregar(e.id(), "PROFESOR", "Eso es lo importante: que sepas que no estás solo en esto. "
                                    + "Nos vemos el martes y revisamos cómo te fue con las entregas.",
                            "TEXTO", null, null, null, base.plusMinutes(104), true);
                    agregar(e.id(), "ESTUDIANTE", "Listo profe. Le cuento cómo me va.",
                            "TEXTO", null, null, null, base.plusMinutes(118), false);
                }
            }
        }
        sembrarConversacionEstudianteDemo();
    }

    /**
     * Hilo curado entre la estudiante demo y su profesor, con mensajes repartidos en más de un año
     * para que el rol Estudiante vea una conversación con recorrido.
     */
    private void sembrarConversacionEstudianteDemo() {
        int id = EstudianteService.ESTUDIANTE_DEMO_ID;
        if (mensajes.containsKey(id)) {
            return;
        }
        LocalDateTime ahora = LocalDateTime.now();
        agregar(id, "PROFESOR", "Hola Mariana, soy Carlos Andrés, tu profesor acompañante. "
                        + "Vi que tuviste algunas dificultades al inicio de tu proceso y quiero saber cómo estás.",
                "TEXTO", null, null, null, ahora.minusMonths(26).withHour(9).withMinute(10), true);
        agregar(id, "ESTUDIANTE", "Buenos días profe. Gracias por escribirme. En casa se complicó la situación "
                        + "económica y por eso falté varias veces.",
                "TEXTO", null, null, null, ahora.minusMonths(26).withHour(10).withMinute(5), true);
        agregar(id, "PROFESOR", "Gracias por contarme. Vamos a revisar apoyos de matrícula y transporte. "
                        + "No estás sola en esto.",
                "TEXTO", null, null, null, ahora.minusMonths(25).withHour(8).withMinute(40), true);
        agregar(id, "ESTUDIANTE", "Profe, ya me aprobaron el apoyo de matrícula. Ahora estoy tratando de "
                        + "organizarme mejor con las entregas.",
                "TEXTO", null, null, null, ahora.minusMonths(19).withHour(16).withMinute(20), true);
        agregar(id, "PROFESOR", "Excelente noticia. Te dejo una tutoría con el monitor de Bases de Datos y "
                        + "otra con trabajo social para revisar el tema del transporte.",
                "TEXTO", null, null, null, ahora.minusMonths(18).withHour(9).withMinute(0), true);
        agregar(id, "ESTUDIANTE", "Muchas gracias profe. Las tutorías me han servido un montón. "
                        + "Este corte saqué 4.0 en Arquitectura.",
                "TEXTO", null, null, null, ahora.minusMonths(3).withHour(18).withMinute(35), true);
        agregar(id, "PROFESOR", "Se nota el cambio, Mariana. Pasaste de riesgo alto a medio y tus notas "
                        + "vienen subiendo. Sigamos con el mismo plan.",
                "TEXTO", null, null, null, ahora.minusMonths(2).withHour(11).withMinute(15), true);
        agregar(id, "ESTUDIANTE", "Sí profe. Esta semana tengo que entregar el proyecto de pruebas. "
                        + "¿Puedo pasar por su oficina si me surge una duda?",
                "TEXTO", null, null, null, ahora.minusDays(2).withHour(15).withMinute(50), true);
        agregar(id, "PROFESOR", "Claro que sí. Te espero mañana a las 10:00 en coordinación. "
                        + "Lleva el avance y lo revisamos juntos.",
                "TEXTO", null, null, null, ahora.minusDays(1).withHour(8).withMinute(30), false);
    }

    private String respuestaInicial(Estudiante e) {
        return switch (e.categoria()) {
            case "Crisis económica" ->
                    "Gracias profe. En casa estamos complicados con la matrícula y por eso he faltado.";
            case "Depresión familiar" ->
                    "Gracias por preguntar, profe. No he podido concentrarme por lo que está pasando en mi casa.";
            case "Desconexión al futuro" ->
                    "Profe, la verdad estoy dudando si esta carrera es para mí. No sé si seguir.";
            case "Estrés académico" ->
                    "Sí profe, me siento muy sobrepasado con los parciales. No sé por dónde empezar.";
            default -> "Gracias profe. Últimamente no le veo mucho sentido a las clases.";
        };
    }

    private String respuestaAutomatica(Estudiante e) {
        String[] respuestas = switch (e.categoria()) {
            case "Crisis económica" -> new String[]{
                    "Gracias profe. La verdad en casa estamos viendo cómo pagar la matrícula.",
                    "Sí profe, me preocupa no poder continuar el próximo semestre.",
                    "¿Usted cree que haya alguna beca o apoyo al que pueda aplicar?"};
            case "Depresión familiar" -> new String[]{
                    "Gracias por escucharme, profe. En casa no la estoy pasando bien.",
                    "Sí, profe. A veces no tengo ánimo ni para levantarme a clase.",
                    "¿Podemos hablar con calma? No se lo he contado a muchos."};
            case "Desconexión al futuro" -> new String[]{
                    "Profe, siento que esta carrera no es lo que esperaba.",
                    "He pensado en cambiarme, pero no sé qué hacer.",
                    "¿Usted cree que valga la pena seguir o sería mejor pausar?"};
            case "Estrés académico" -> new String[]{
                    "Sí profe, los parciales me tienen muy ansioso.",
                    "No sé cómo organizarme con tantas entregas.",
                    "¿Podría ayudarme a armar un plan para no perder las materias?"};
            default -> new String[]{
                    "Gracias profe. La verdad he estado muy desanimado.",
                    "Sí, profe. Siento que las clases no me están aportando.",
                    "¿Podemos hablar de lo que me gustaría hacer más adelante?"};
        };
        return respuestas[random.nextInt(respuestas.length)];
    }

    /** Respuesta breve del profesor acompañante en la conversación del estudiante. */
    private String respuestaProfesor(Estudiante e) {
        String[] respuestas = {
                "Gracias por escribirme, " + e.nombre() + ". Cuéntame cómo te fue esta semana y lo revisamos juntos.",
                "Muy bien, " + e.nombre() + ". Recuerda que podemos ajustar el plan si alguna entrega se complica.",
                "Te escucho. Si quieres, agendamos una cita corta con bienestar y miramos alternativas.",
                "Ese esfuerzo se nota. Sigamos así y revisemos las notas del próximo corte."
        };
        return respuestas[random.nextInt(respuestas.length)];
    }

    @PreDestroy
    void cerrar() {
        scheduler.shutdownNow();
    }
}
