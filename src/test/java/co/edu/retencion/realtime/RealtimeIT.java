package co.edu.retencion.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.realtime.infrastructure.RealtimeSessions;
import co.edu.retencion.support.AttendanceWorld;
import co.edu.retencion.support.AttendanceWorld.Actor;
import co.edu.retencion.support.CampusIntegrationTest;
import co.edu.retencion.support.IntegrationTest.Tokens;
import co.edu.retencion.support.OfficeFiles;

/**
 * Pruebas e2e con un cliente STOMP real sobre WebSocket: autenticación del CONNECT, autorización de
 * suscripciones, campanita en tiempo real al docente correcto y chat con texto y adjunto.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealtimeIT extends CampusIntegrationTest {

    private static final long WAIT_SECONDS = 10;

    @LocalServerPort
    int port;
    @Autowired
    RealtimeSessions sessions;

    private final List<Client> clients = new ArrayList<>();

    @AfterEach
    void disconnect() {
        clients.forEach(Client::close);
        clients.clear();
    }

    /** Cliente STOMP de prueba: guarda los frames ERROR y si se perdió la conexión. */
    private final class Client extends StompSessionHandlerAdapter {

        final BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        final BlockingQueue<Throwable> transport = new LinkedBlockingQueue<>();
        final WebSocketStompClient stomp;
        StompSession session;

        Client() {
            stomp = new WebSocketStompClient(new StandardWebSocketClient());
            MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
            converter.setObjectMapper(json);
            stomp.setMessageConverter(converter);
        }

        CompletableFuture<StompSession> connect(String token) {
            StompHeaders headers = new StompHeaders();
            if (token != null) {
                headers.add("Authorization", "Bearer " + token);
            }
            return stomp.connectAsync("ws://localhost:" + port + "/ws/websocket", new WebSocketHttpHeaders(), headers, this);
        }

        Client connected(Tokens tokens) throws Exception {
            session = connect(tokens.accessToken()).get(WAIT_SECONDS, TimeUnit.SECONDS);
            clients.add(this);
            return this;
        }

        BlockingQueue<JsonNode> subscribe(String destination) {
            BlockingQueue<JsonNode> queue = new LinkedBlockingQueue<>();
            session.subscribe(destination, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return JsonNode.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    queue.add((JsonNode) payload);
                }
            });
            return queue;
        }

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return byte[].class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            errors.add(String.valueOf(headers.getFirst("message")));
        }

        @Override
        public void handleException(StompSession s, StompCommand command, StompHeaders headers, byte[] payload,
                                    Throwable exception) {
            transport.add(exception);
        }

        @Override
        public void handleTransportError(StompSession s, Throwable exception) {
            transport.add(exception);
        }

        boolean closedWithin(long seconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (System.nanoTime() < deadline) {
                if (session != null && !session.isConnected()) {
                    return true;
                }
                Thread.sleep(100);
            }
            return false;
        }

        void close() {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
            stomp.stop();
        }
    }

    private static JsonNode next(BlockingQueue<JsonNode> queue) throws InterruptedException {
        JsonNode value = queue.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(value).as("mensaje esperado en tiempo real").isNotNull();
        return value;
    }

    @Test
    void connectWithoutTokenOrWithExpiredTokenIsRejected() throws Exception {
        Client anonymous = new Client();
        clients.add(anonymous);
        assertThatThrownBy(() -> anonymous.connect(null).get(WAIT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOfAny(ExecutionException.class, TimeoutException.class);
        assertThat(anonymous.errors.poll(WAIT_SECONDS, TimeUnit.SECONDS)).isNotNull();

        Client forged = new Client();
        clients.add(forged);
        assertThatThrownBy(() -> forged.connect("no.es.un-jwt").get(WAIT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOfAny(ExecutionException.class, TimeoutException.class);

        // Token emitido hace 20 minutos (vida máxima 15): vencido.
        clock.setNow(clock.instant().minus(Duration.ofMinutes(20)));
        Actor teacher = staff(Set.of(Role.DOCENTE), Set.of());
        clock.reset();
        Client expired = new Client();
        clients.add(expired);
        assertThatThrownBy(() -> expired.connect(teacher.tokens().accessToken()).get(WAIT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOfAny(ExecutionException.class, TimeoutException.class);
        assertThat(expired.errors.poll(WAIT_SECONDS, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void sessionsCloseWhenTheTokenExpiresOrOnLogout() throws Exception {
        Actor teacher = staff(Set.of(Role.DOCENTE), Set.of());
        Client byExpiry = new Client().connected(teacher.tokens());
        relogin(teacher);
        Client byLogout = new Client().connected(teacher.tokens());

        mvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer(teacher.tokens())))
                .andExpect(status().is2xxSuccessful());
        assertThat(byLogout.closedWithin(WAIT_SECONDS)).as("logout cierra la sesión WebSocket").isTrue();
        assertThat(byExpiry.session.isConnected()).as("otra sesión del mismo usuario sigue viva").isTrue();

        clock.setNow(clock.instant().plus(Duration.ofMinutes(16)));
        sessions.closeExpired();
        assertThat(byExpiry.closedWithin(WAIT_SECONDS)).as("el token venció").isTrue();
    }

    @Test
    void riskReachesOnlyTheRightTeacherAndChatIsDeliveredToTheStudent() throws Exception {
        AttendanceWorld w = highRiskWorld(ISW);
        setting("ai.enabled", false);
        Actor stranger = staff(Set.of(Role.DOCENTE), Set.of());
        Actor counselor = staff(Set.of(Role.CONSEJERO), Set.of(ISW));
        relogin(w.teacher(), w.student());

        Client teacher = new Client().connected(w.teacher().tokens());
        Client outsider = new Client().connected(stranger.tokens());
        Client student = new Client().connected(w.student().tokens());
        BlockingQueue<JsonNode> teacherBell = teacher.subscribe("/user/queue/notifications");
        BlockingQueue<JsonNode> outsiderBell = outsider.subscribe("/user/queue/notifications");
        BlockingQueue<JsonNode> studentChat = student.subscribe("/user/queue/chat");
        BlockingQueue<JsonNode> teacherChat = teacher.subscribe("/user/queue/chat");
        Thread.sleep(300);

        // 1. Riesgo detectado → campanita del docente del grupo, en tiempo real y sin datos sensibles.
        analyze(counselor, w.studentId());
        JsonNode push = next(teacherBell);
        assertThat(push.get("type").asText()).isEqualTo("RIESGO_ESTUDIANTE");
        assertThat(push.get("studentId").asText()).isEqualTo(w.studentId().toString());
        assertThat(push.get("riskLevel").asText()).isEqualTo("ALTO");
        assertThat(push.has("studentName")).as("el push no lleva el nombre").isFalse();
        assertThat(outsiderBell.poll(2, TimeUnit.SECONDS)).as("docente sin relación").isNull();

        JsonNode bell = notifications(w.teacher());
        JsonNode risk = null;
        for (JsonNode n : bell) {
            if (n.get("id").asText().equals(push.get("id").asText())) {
                risk = n;
            }
        }
        assertThat(risk).isNotNull();
        assertThat(risk.get("studentName").asText()).isNotBlank();
        assertThat(risk.get("message").asText()).contains("Materia de prueba").contains("3 faltas");
        List<String> keys = new ArrayList<>();
        risk.get("reason").fieldNames().forEachRemaining(keys::add);
        assertThat(keys).isSubsetOf("subject", "groupId", "average", "trend", "absences", "maxAbsences",
                "attendanceLevel");
        String reason = risk.get("reason").toString().toLowerCase();
        assertThat(reason).doesNotContain("estrato").doesNotContain("socio").doesNotContain("bienestar")
                .doesNotContain("salud").doesNotContain("trabaja").doesNotContain("transporte");
        assertThat(risk.at("/openChat/path").asText()).isEqualTo("/api/v1/chats");
        assertThat(notifications(stranger).findValuesAsText("studentId")).doesNotContain(w.studentId().toString());

        // 2. Abrir chat desde la notificación y escribir por STOMP.
        relogin(w.teacher());
        UUID chat = UUID.fromString(read(postJson("/api/v1/chats", w.teacher().tokens(),
                Map.of("studentId", w.studentId())).andExpect(status().isOk())).get("id").asText());
        postJson("/api/v1/chats", stranger.tokens(), Map.of("studentId", w.studentId()))
                .andExpect(status().isForbidden());
        teacher.session.send("/app/chat.send", Map.of("conversationId", chat, "type", "TEXTO",
                "text", "Hola, vi que tienes 3 faltas. ¿Hablamos?"));
        JsonNode text = next(studentChat);
        assertThat(text.get("conversationId").asText()).isEqualTo(chat.toString());
        assertThat(text.get("text").asText()).isEqualTo("Hola, vi que tienes 3 faltas. ¿Hablamos?");
        assertThat(text.get("senderId").asText()).isEqualTo(w.teacher().userId().toString());
        next(teacherChat);

        // 3. Adjunto: se sube por REST, se envía por STOMP con su id y llega al estudiante.
        String attachment = read(upload(w.teacher(), chat, "guia.pdf", OfficeFiles.PDF, null)
                .andExpect(status().isCreated())).get("id").asText();
        teacher.session.send("/app/chat.send", Map.of("conversationId", chat, "type", "DOCUMENTO",
                "attachmentId", attachment));
        JsonNode file = next(studentChat);
        assertThat(file.get("type").asText()).isEqualTo("DOCUMENTO");
        assertThat(file.at("/attachment/id").asText()).isEqualTo(attachment);
        assertThat(file.at("/attachment/name").asText()).isEqualTo("guia.pdf");
        assertThat(file.toString()).doesNotContain("http");

        // 4. Un intruso no se suscribe al chat ni a la cola de otro usuario.
        BlockingQueue<JsonNode> stolenTopic = outsider.subscribe("/topic/chats." + chat);
        assertThat(outsider.errors.poll(WAIT_SECONDS, TimeUnit.SECONDS)).isNotNull();
        assertThat(stolenTopic.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void cannotSubscribeToAnotherUsersQueueOrSendOutsideApp() throws Exception {
        Actor a = staff(Set.of(Role.DOCENTE), Set.of());
        Actor b = staff(Set.of(Role.DOCENTE), Set.of());
        Client client = new Client().connected(a.tokens());
        client.subscribe("/user/" + b.userId() + "/queue/notifications");
        assertThat(client.errors.poll(WAIT_SECONDS, TimeUnit.SECONDS)).isNotNull();

        Client other = new Client().connected(a.tokens());
        other.subscribe("/queue/notifications-user" + b.userId());
        assertThat(other.errors.poll(WAIT_SECONDS, TimeUnit.SECONDS)).isNotNull();

        Client sender = new Client().connected(a.tokens());
        sender.session.send("/topic/chats." + UUID.randomUUID(), Map.of("text", "hola"));
        assertThat(sender.errors.poll(WAIT_SECONDS, TimeUnit.SECONDS)).isNotNull();
    }
}
