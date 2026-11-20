package co.edu.permanencia.demo.risk;

import co.edu.permanencia.demo.students.Estudiante;
import co.edu.permanencia.demo.students.NotaMateria;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueba obligatoria del proyecto: ningún payload hacia la IA contiene datos personales.
 *
 * <p>Usa un servidor HTTP local que imita la API de mensajes: así se prueba el SDK real (modelo,
 * cuerpo enviado y lectura de la respuesta) sin red ni clave.</p>
 */
class ClaudeSinDatosPersonalesTest {

    private static final Estudiante ESTUDIANTE = new Estudiante(77, "Valentina", "Ortiz Gómez", "1090009368",
            "Enfermería", "Facultad de Salud", "Neiva", 3, "B", "valentina.ortiz@ucc.edu.co", "3084185192",
            "Crisis económica",
            "Valentina dice que no puede pagar; llamarla al 308 418 5192 o escribir a valentina.ortiz@ucc.edu.co",
            2.8, 5, "ALTO", 88, "ACTIVO", "2025-07-15",
            List.of(new NotaMateria("Anatomía", 2.6)), List.of(60, 75, 88));

    private HttpServer servidor;
    private final AtomicReference<String> cuerpoRecibido = new AtomicReference<>();
    private volatile String respuesta;

    @BeforeEach
    void iniciar() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/v1/messages", intercambio -> {
            cuerpoRecibido.set(new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = respuesta.getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            intercambio.sendResponseHeaders(200, bytes.length);
            intercambio.getResponseBody().write(bytes);
            intercambio.close();
        });
        servidor.start();
    }

    @AfterEach
    void detener() {
        servidor.stop(0);
    }

    private ClienteClaude cliente() {
        return new ClienteClaude("clave-de-prueba", "claude-sonnet-5-5",
                "http://127.0.0.1:" + servidor.getAddress().getPort(), 10);
    }

    private static String mensajeApi(String bloques) {
        return """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5-5",
                 "content":[%s],"stop_reason":"end_turn","stop_sequence":null,
                 "usage":{"input_tokens":10,"output_tokens":20}}""".formatted(bloques);
    }

    @Test
    void elPayloadNoLlevaNombreDocumentoCorreoTelefonoNiSede() {
        respuesta = mensajeApi("{\"type\":\"text\",\"text\":\"Plan sugerido.\"}");
        String caso = AnalisisService.datosParaIa(ESTUDIANTE, List.of("Agendar tutoría de Anatomía."), false);

        assertThat(cliente().redactar(caso)).contains("Plan sugerido.");

        String enviado = cuerpoRecibido.get();
        assertThat(enviado).contains("\"model\":\"claude-sonnet-5-5\"").contains("Enfermería").contains("Anatomía");
        assertThat(enviado).doesNotContain("Valentina", "Ortiz", "1090009368", "valentina.ortiz",
                "3084185192", "308 418 5192", "Neiva", "no puede pagar");
    }

    @Test
    void conTextoLibreActivadoElMotivoSeRedacta() {
        String caso = AnalisisService.datosParaIa(ESTUDIANTE, List.of(), true);
        assertThat(caso).contains("no puede pagar").contains("[nombre]").contains("[número]").contains("[correo]");
        assertThat(caso).doesNotContain("Valentina", "valentina.ortiz", "308 418 5192");
    }

    @Test
    void leeElTextoAunqueNoSeaElPrimerBloque() {
        respuesta = mensajeApi("{\"type\":\"thinking\",\"thinking\":\"...\",\"signature\":\"abc\"},"
                + "{\"type\":\"text\",\"text\":\"Primera parte. \"},{\"type\":\"text\",\"text\":\"Segunda parte.\"}");
        assertThat(cliente().redactar("caso")).contains("Primera parte. Segunda parte.");
    }

    @Test
    void sinClaveNoLlamaALaApi() {
        ClienteClaude sinClave = new ClienteClaude("", "claude-sonnet-5-5", "http://127.0.0.1:1", 1);
        assertThat(sinClave.disponible()).isFalse();
        assertThat(sinClave.redactar("caso")).isEmpty();
    }

    @Test
    void siLaApiFallaSeConservaElMotorLocal() {
        servidor.removeContext("/v1/messages");
        servidor.createContext("/v1/messages", intercambio -> {
            byte[] bytes = "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"x\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            intercambio.sendResponseHeaders(500, bytes.length);
            intercambio.getResponseBody().write(bytes);
            intercambio.close();
        });
        ClienteClaude cliente = new ClienteClaude("clave", "claude-sonnet-5-5",
                "http://127.0.0.1:" + servidor.getAddress().getPort(), 5);
        assertThat(cliente.redactar("caso")).isEmpty();
    }
}
